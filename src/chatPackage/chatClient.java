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

    /** 服务器消息回调接口（由 UI 层实现） */
    public interface Listener {
        void onSystemMessage(String content);          // SYSTEM:xxx 系统消息
        void onChatMessage(String sender, String content); // MSG:昵称:内容 聊天消息
        void onUserList(String[] users);               // USERS:... 在线用户列表
        void onDisconnected(String reason);            // 连接断开（异常/被服务器关闭/主动退出）
    }

    /** 连接超时时间（毫秒），局域网连接不上时避免长时间卡住 */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean connected;
    private volatile boolean notifiedDisconnect;
    private volatile Listener listener;

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
    }

    /** 登录（应放在注册监听器之后调用） */
    public void login(String nickname) {
        sendLine("LOGIN:" + nickname);
    }

    /** 发送一条聊天消息 */
    public void sendMessage(String content) {
        sendLine("MSG:" + content);
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
        if (line.startsWith("SYSTEM:")) {
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
            listener.onUserList(users);
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
