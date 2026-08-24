package chatPackage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 聊天室客户端网络层（与 chatServer 配对的客户端类）
 *
 * 线程模型：
 *  - 接收线程（chat-client-receiver）：readLine() 循环持续读取服务端消息
 *  - 消息回调发生在接收线程上，UI 层必须在回调中通过 SwingUtilities.invokeLater 切回 EDT 再更新界面
 *
 * 使用流程：
 *  chatClient client = new chatClient();
 *  client.connect(ip, port);      // 1. 建立 TCP 连接（可先设置监听器，也可后设置）
 *  client.setListener(ui);        // 2. 注册回调
 *  client.login(nickname);        // 3. 登录（在 UI 就绪后调用，避免漏掉服务端广播的消息）
 *  client.sendMessage(content);   // 4. 发消息
 *  client.logout();               // 5. 退出
 */
public class chatClient {

    /** 服务器消息回调接口（由 UI 层实现；新回调均为 default 实现，老实现无需改动） */
    public interface Listener {
        void onLoginResult(boolean success, String reason);   // LOGINOK / LOGINFAIL:原因
        void onRegisterResult(boolean success, String reason); // REGISTEROK / REGISTERFAIL:原因
        void onSystemMessage(String content);          // SYSTEM:xxx 系统消息
        void onChatMessage(String sender, String content); // MSG:昵称:内容 聊天消息
        void onUserList(String[] users);               // USERS:... 在线用户列表
        void onOfflineUsers(String[] users);           // OFFLINEUSERS:... 离线用户列表
        void onDisconnected(String reason);            // 连接断开（异常/被服务器关闭/主动退出）

        /** 登录成功后服务端下发的角色：admin / user */
        default void onRole(String role) {}

        /** 本连接被管理员踢出 */
        default void onKicked(String reason) {}

        /** 本连接被管理员封禁（账号已删除） */
        default void onBanned(String reason) {}
    }

    /** 连接超时时间（毫秒），局域网连接不上时避免长时间卡住 */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean connected;
    private volatile boolean notifiedDisconnect;
    private volatile Listener listener;

    // 最近一次的状态缓存：
    // 登录后的 LOGINOK/USERS/OFFLINEUSERS 广播到达时 UI 监听器可能还没挂载
    // （登录界面先用临时监听器等待结果，之后再切换到聊天窗口监听器），
    // 缓存下来并在 setListener 时补发，保证挂上监听器就能立即拿到角色和用户列表
    private volatile String lastRole;
    private volatile String[] lastOnlineUsers;
    private volatile String[] lastOfflineUsers;

    /**
     * 建立与服务端的 TCP 连接，并启动接收线程。
     * 注意：这里不发送 LOGIN，登录由 UI 就绪后调用 {@link #login(String)} 完成，
     * 这样能保证注册监听器之前不会丢消息（服务端只在收到 LOGIN 后才开始广播）。
     */
    public void connect(String ip, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        connected = true;
        notifiedDisconnect = false;
        Thread receiver = new Thread(this::receiveLoop, "chat-client-receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        // 补发缓存的状态，避免监听器挂载前到达的广播丢失
        // （修复登录后列表迟迟不显示、管理员角色丢失的问题）
        if (listener != null) {
            if (lastRole != null) {
                listener.onRole(lastRole);
            }
            if (lastOnlineUsers != null) {
                listener.onUserList(lastOnlineUsers);
            }
            if (lastOfflineUsers != null) {
                listener.onOfflineUsers(lastOfflineUsers);
            }
        }
    }

    /** 登录（应放在注册监听器之后调用），结果通过 onLoginResult 回调通知 */
    public void login(String username, String password) {
        sendLine("LOGIN:" + username + ":" + password);
    }

    /** 注册新账号，结果通过 onRegisterResult 回调通知 */
    public void register(String username, String password) {
        sendLine("REGISTER:" + username + ":" + password);
    }

    /** 发送一条聊天消息 */
    public void sendMessage(String content) {
        sendLine("MSG:" + content);
    }

    /** 管理员：踢出在线用户 */
    public void kick(String username) {
        sendLine("KICK:" + username);
    }

    /** 管理员：封禁用户并从数据库删除其账号 */
    public void ban(String username) {
        sendLine("BAN:" + username);
    }

    /** 主动退出：发送 LOGOUT 并关闭连接 */
    public void logout() {
        try {
            sendLine("LOGOUT");
        } finally {
            connected = false;
            closeResources();
        }
    }

    private void sendLine(String line) {
        if (!connected || out == null) {
            return;
        }
        out.println(line);
        if (out.checkError()) {
            // 写入失败说明连接已断开
            onDisconnect("发送消息失败，连接已断开");
        }
    }

    /** 接收线程：持续读取服务端发来的每一行消息 */
    private void receiveLoop() {
        try {
            String line;
            while (connected && (line = in.readLine()) != null) {
                handleServerMessage(line);
            }
            if (connected) {
                onDisconnect("服务器已关闭连接");
            }
        } catch (IOException e) {
            if (connected) {
                onDisconnect("连接异常中断：" + e.getMessage());
            }
        }
    }

    /** 解析服务端发来的消息并回调监听器 */
    private void handleServerMessage(String line) {
        if (listener == null) {
            return;
        }
        if (line.startsWith("LOGINOK")) {
            // LOGINOK 或 LOGINOK:角色（admin / user）
            String role = line.startsWith("LOGINOK:") ? line.substring("LOGINOK:".length()) : "user";
            lastRole = role; // 缓存角色，供监听器挂载时补发
            listener.onLoginResult(true, "");
            listener.onRole(role);
        } else if (line.startsWith("LOGINFAIL:")) {
            listener.onLoginResult(false, line.substring("LOGINFAIL:".length()));
        } else if (line.equals("REGISTEROK")) {
            listener.onRegisterResult(true, "");
        } else if (line.startsWith("REGISTERFAIL:")) {
            listener.onRegisterResult(false, line.substring("REGISTERFAIL:".length()));
        } else if (line.startsWith("SYSTEM:")) {
            listener.onSystemMessage(line.substring("SYSTEM:".length()));
        } else if (line.startsWith("MSG:")) {
            // MSG:昵称:内容 —— 取第一个冒号做分隔符，消息内容本身可以含冒号
            int colon = line.indexOf(':', "MSG:".length());
            if (colon < 0) {
                return;
            }
            String sender = line.substring("MSG:".length(), colon);
            String content = line.substring(colon + 1);
            listener.onChatMessage(sender, content);
        } else if (line.startsWith("USERS:")) {
            String list = line.substring("USERS:".length());
            String[] users = list.isEmpty() ? new String[0] : list.split(",");
            lastOnlineUsers = users; // 缓存，供监听器挂载时补发
            listener.onUserList(users);
        } else if (line.startsWith("OFFLINEUSERS:")) {
            String list = line.substring("OFFLINEUSERS:".length());
            String[] users = list.isEmpty() ? new String[0] : list.split(",");
            lastOfflineUsers = users; // 缓存，供监听器挂载时补发
            listener.onOfflineUsers(users);
        } else if (line.startsWith("KICKED:")) {
            listener.onKicked(line.substring("KICKED:".length()));
        } else if (line.startsWith("BANNED:")) {
            listener.onBanned(line.substring("BANNED:".length()));
        }
    }

    /** 统一断开处理：置断开标志并通知监听器（保证只通知一次） */
    private void onDisconnect(String reason) {
        if (!connected) {
            return;
        }
        connected = false;
        if (!notifiedDisconnect) {
            notifiedDisconnect = true;
            if (listener != null) {
                listener.onDisconnected(reason);
            }
        }
        closeResources();
    }

    private void closeResources() {
        try {
            if (out != null) out.close();
        } catch (Exception ignored) {
        }
        try {
            if (in != null) in.close();
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}
