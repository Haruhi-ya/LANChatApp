package chatPackage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

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
        void onChatMessage(String sender, String msgId, long timestamp, String content); // MSG:昵称:消息ID:时间戳:内容
        void onUserList(String[] users);               // USERS:... 在线用户列表
        void onOfflineUsers(String[] users);           // OFFLINEUSERS:... 离线用户列表
        void onDisconnected(String reason);            // 连接断开（异常/被服务器关闭/主动退出）

        /** 登录成功后服务端下发的角色：admin / user */
        default void onRole(String role) {}

        /** 本连接被管理员踢出 */
        default void onKicked(String reason) {}

        /** 本连接被管理员封禁（账号已删除） */
        default void onBanned(String reason) {}

        // ===== 公共聊天历史回放 =====

        default void onPublicHistoryBegin() {}
        default void onPublicHistoryItem(String sender, String msgId, long timestamp, String content) {}
        default void onPublicHistoryEnd() {}

        /** 公共聊天记录已被管理员清空 */
        default void onPublicCleared(String operator) {}

        // ===== 私聊 =====

        /**
         * 收到一条私聊消息。
         *
         * @param peer      会话对方（自己发出的消息里是接收者，收到的消息里是发送者）
         * @param sender    实际发送者
         * @param msgId     服务端生成的撤回定位 ID（老消息可能为空字符串）
         * @param unread    服务端给出的权威未读数，客户端直接显示这个值，不要本地自增
         */
        default void onPrivateMessage(String peer, String sender, String msgId, long timestamp,
                                      int unread, String content) {}

        default void onPrivateHistoryBegin(String peer) {}
        default void onPrivateHistoryItem(String peer, String sender, String msgId, long timestamp, String content) {}
        default void onPrivateHistoryEnd(String peer) {}

        /** 自己与某人的私聊记录已清空 */
        default void onPrivateCleared(String peer) {}

        /** 私聊操作失败（对方不存在、发送失败等） */
        default void onPrivateFail(String peer, String reason) {}

        /** 未读私聊汇总：对方用户名 -> 未读条数 */
        default void onUnreadSummary(Map<String, Integer> counts) {}

        // ===== 消息撤回 =====

        /** 公共消息已被撤回（msgId 定位气泡，byWho 是操作者） */
        default void onRecalled(String msgId, String byWho) {}

        /** 私聊消息已被撤回（peer 是会话对方） */
        default void onPrivateRecalled(String peer, String msgId, String byWho) {}

        /** 撤回被拒（超时/无权/不存在） */
        default void onRecallFail(String msgId, String reason) {}

        // ===== 搜索与@提醒 =====

        /** 搜索结果回放：peer 为 "PUBLIC" 表示公共频道，否则是私聊会话对方 */
        default void onSearchResultBegin(String peer) {}
        default void onSearchResultItem(String peer, String msgId, String sender, long timestamp, String content) {}
        default void onSearchResultEnd(String peer) {}
        default void onSearchFail(String peer, String reason) {}

        /** 公共消息里被 @ 了（from 是 @ 你的人） */
        default void onAttention(String from) {}

        // ===== 头像 =====

        /** GETAVATAR 响应：data 为图片字节；data == null 表示该用户未设置头像 */
        default void onAvatar(String name, byte[] data) {}

        /** 某人头像已变更（AVATARCHG）：清缓存，下次绘制时按需重拉 */
        default void onAvatarChanged(String name) {}

        /** SETAVATAR 结果：上传成功 / 失败原因 */
        default void onAvatarResult(boolean success, String reason) {}
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

    /** 发送一条私聊消息（对方在线或离线都可以发） */
    public void sendPrivate(String peer, String content) {
        sendLine("PM:" + peer + ":" + content);
    }

    /** 拉取与某人的私聊历史，结果通过 onPrivateHistoryBegin/Item/End 回调 */
    public void requestPrivateHistory(String peer) {
        sendLine("PMHIST:" + peer);
    }

    /** 标记与某人的私聊为已读（私聊窗口开着时收到新消息后调用） */
    public void markPrivateRead(String peer) {
        sendLine("PMREAD:" + peer);
    }

    /** 清空自己与某人的私聊记录（不影响对方那份） */
    public void clearPrivateHistory(String peer) {
        sendLine("CLEARPM:" + peer);
    }

    /** 管理员：清空公共聊天记录 */
    public void clearPublicHistory() {
        sendLine("CLEARPUBLIC");
    }

    /** 拉取公共聊天历史，结果通过 onPublicHistoryBegin/Item/End 回调 */
    public void requestPublicHistory() {
        sendLine("HIST");
    }

    /** 拉取未读私聊汇总，结果通过 onUnreadSummary 回调 */
    public void requestUnread() {
        sendLine("UNREAD");
    }

    /** 撤回一条公共消息（只能撤自己的；管理员可撤任何人的） */
    public void recall(String msgId) {
        sendLine("RECALL:" + msgId);
    }

    /** 撤回一条私聊消息（只能撤自己的） */
    public void recallPrivate(String peer, String msgId) {
        sendLine("PMRECALL:" + peer + ":" + msgId);
    }

    /** 搜索公共聊天记录 */
    public void searchPublic(String keyword) {
        sendLine("SEARCHPUB:" + keyword);
    }

    /** 搜索与某人的私聊记录 */
    public void searchPrivate(String peer, String keyword) {
        sendLine("SEARCHPM:" + peer + ":" + keyword);
    }

    /** 拉取某用户的头像，结果通过 onAvatar 回调 */
    public void getAvatar(String username) {
        sendLine("GETAVATAR:" + username);
    }

    /** 上传自己的头像（base64 为空字符串 = 移除头像），结果通过 onAvatarResult 回调 */
    public void setAvatar(String base64) {
        sendLine("SETAVATAR:" + base64);
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

    /**
     * 发送一行协议消息。所有发送都经过这里，因此在这里统一剔除换行符。
     *
     * 协议是「每行一条命令」，服务端 readLine() 会把 \r、\n、\r\n 都当行结束符。
     * 内容里混进换行符就会被拆成两行，后半段被服务端当成独立命令执行（详见
     * chatServer.sanitize 的注释）。注意 JTextField 只过滤 \n 不过滤 \r，
     * 所以从 Windows 文本粘贴的内容确实会带 \r 过来。
     *
     * 这层只是客户端兜底，真正的收口在服务端——手工构造 socket 可以绕过这里。
     */
    private void sendLine(String line) {
        if (!connected || out == null) {
            return;
        }
        out.println(line.replace('\r', ' ').replace('\n', ' '));
        if (out.checkError()) {
            // 写入失败说明连接已断开
            onDisconnect("发送消息失败，连接已断开");
        }
    }

    /**
     * 按固定字段数切分协议消息：返回长度为 n+1 的数组，前 n 个是冒号分隔的固定字段，
     * 最后一个是第 n 个冒号之后的全部内容（可以包含冒号）。字段不足时返回 null。
     *
     * 不能用 line.split(":")：消息内容里的冒号会被当成分隔符，把内容截断。
     * 用户名已由服务端禁止包含冒号，时间戳和计数是纯数字，所以前面的字段切分是安全的。
     *
     * @param body 已经去掉命令前缀的部分
     * @param n    内容之前的固定字段个数
     */
    private static String[] splitFixed(String body, int n) {
        String[] parts = new String[n + 1];
        int from = 0;
        for (int i = 0; i < n; i++) {
            int colon = body.indexOf(':', from);
            if (colon < 0) {
                return null;
            }
            parts[i] = body.substring(from, colon);
            from = colon + 1;
        }
        parts[n] = body.substring(from);
        return parts;
    }

    /** 解析纯数字字段，非法时返回 fallback（防御损坏或跨版本的消息） */
    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 解析未读汇总：对方:数量,对方2:数量2
     *
     * 分隔符用逗号和冒号而不是等号：等号是合法用户名字符（服务端 isValidName 只禁了
     * 冒号和逗号），用 a=3 的形式遇到名字里带等号的用户就无法无歧义解析了。
     */
    private static Map<String, Integer> parseUnread(String body) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (body.isEmpty()) {
            return counts;
        }
        for (String pair : body.split(",")) {
            int colon = pair.lastIndexOf(':');
            if (colon <= 0) {
                continue;
            }
            counts.put(pair.substring(0, colon), (int) parseLong(pair.substring(colon + 1), 0L));
        }
        return counts;
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
            // MSG:昵称:消息ID:时间戳:内容
            String[] p = splitFixed(line.substring("MSG:".length()), 3);
            if (p != null) {
                listener.onChatMessage(p[0], p[1], parseLong(p[2], System.currentTimeMillis()), p[3]);
            }
        } else if (line.equals("HISTBEGIN")) {
            listener.onPublicHistoryBegin();
        } else if (line.startsWith("HISTITEM:")) {
            // HISTITEM:昵称:消息ID:时间戳:内容（老消息 msgId 是空字段）
            String[] p = splitFixed(line.substring("HISTITEM:".length()), 3);
            if (p != null) {
                listener.onPublicHistoryItem(p[0], p[1], parseLong(p[2], 0L), p[3]);
            }
        } else if (line.equals("HISTEND")) {
            listener.onPublicHistoryEnd();
        } else if (line.startsWith("PUBLICCLEARED:")) {
            listener.onPublicCleared(line.substring("PUBLICCLEARED:".length()));
        } else if (line.startsWith("PMMSG:")) {
            // PMMSG:对方:发送者:消息ID:时间戳:未读数:内容
            String[] p = splitFixed(line.substring("PMMSG:".length()), 5);
            if (p != null) {
                listener.onPrivateMessage(p[0], p[1], p[2], parseLong(p[3], System.currentTimeMillis()),
                        (int) parseLong(p[4], 0L), p[5]);
            }
        } else if (line.startsWith("PMHISTBEGIN:")) {
            listener.onPrivateHistoryBegin(line.substring("PMHISTBEGIN:".length()));
        } else if (line.startsWith("PMHISTITEM:")) {
            // PMHISTITEM:对方:发送者:消息ID:时间戳:内容（老消息 msgId 是空字段）
            String[] p = splitFixed(line.substring("PMHISTITEM:".length()), 4);
            if (p != null) {
                listener.onPrivateHistoryItem(p[0], p[1], p[2], parseLong(p[3], 0L), p[4]);
            }
        } else if (line.startsWith("PMHISTEND:")) {
            listener.onPrivateHistoryEnd(line.substring("PMHISTEND:".length()));
        } else if (line.startsWith("PMCLEARED:")) {
            listener.onPrivateCleared(line.substring("PMCLEARED:".length()));
        } else if (line.startsWith("PMFAIL:")) {
            // PMFAIL:对方:原因
            String[] p = splitFixed(line.substring("PMFAIL:".length()), 1);
            if (p != null) {
                listener.onPrivateFail(p[0], p[1]);
            }
        } else if (line.startsWith("RECALLED:")) {
            // RECALLED:消息ID:操作者
            String[] p = splitFixed(line.substring("RECALLED:".length()), 1);
            if (p != null) {
                listener.onRecalled(p[0], p[1]);
            }
        } else if (line.startsWith("PMRECALLED:")) {
            // PMRECALLED:对方:消息ID:操作者
            String[] p = splitFixed(line.substring("PMRECALLED:".length()), 2);
            if (p != null) {
                listener.onPrivateRecalled(p[0], p[1], p[2]);
            }
        } else if (line.startsWith("RECALLFAIL:")) {
            // RECALLFAIL:消息ID:原因
            String[] p = splitFixed(line.substring("RECALLFAIL:".length()), 1);
            if (p != null) {
                listener.onRecallFail(p[0], p[1]);
            }
        } else if (line.startsWith("ATMSG:")) {
            listener.onAttention(line.substring("ATMSG:".length()));
        } else if (line.startsWith("SRESULTBEGIN:")) {
            listener.onSearchResultBegin(line.substring("SRESULTBEGIN:".length()));
        } else if (line.startsWith("SRESULT:")) {
            // SRESULT:对方:消息ID:发送者:时间戳:内容
            String[] p = splitFixed(line.substring("SRESULT:".length()), 4);
            if (p != null) {
                listener.onSearchResultItem(p[0], p[1], p[2], parseLong(p[3], 0L), p[4]);
            }
        } else if (line.startsWith("SRESULTEND:")) {
            listener.onSearchResultEnd(line.substring("SRESULTEND:".length()));
        } else if (line.startsWith("SEARCHFAIL:")) {
            // SEARCHFAIL:对方:原因
            String[] p = splitFixed(line.substring("SEARCHFAIL:".length()), 1);
            if (p != null) {
                listener.onSearchFail(p[0], p[1]);
            }
        } else if (line.startsWith("UNREAD:")) {
            listener.onUnreadSummary(parseUnread(line.substring("UNREAD:".length())));
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
        } else if (line.startsWith("AVATAR:")) {
            // AVATAR:用户名:base64（未设置头像时 base64 为空字段）
            String[] p = splitFixed(line.substring("AVATAR:".length()), 1);
            if (p != null) {
                byte[] data = null;
                if (!p[1].isEmpty()) {
                    try {
                        data = Base64.getDecoder().decode(p[1]);
                    } catch (IllegalArgumentException e) {
                        data = null; // 损坏数据按无头像处理，渲染端回退首字母
                    }
                }
                listener.onAvatar(p[0], data);
            }
        } else if (line.startsWith("AVATARCHG:")) {
            listener.onAvatarChanged(line.substring("AVATARCHG:".length()));
        } else if (line.equals("AVATAROK")) {
            listener.onAvatarResult(true, "");
        } else if (line.startsWith("AVATARFAIL:")) {
            listener.onAvatarResult(false, line.substring("AVATARFAIL:".length()));
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
