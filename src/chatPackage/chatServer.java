package chatPackage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天室服务器
 *
 * 线程模型：
 *  - 主线程：ServerSocket.accept() 循环，只负责接收客户端的 socket 连接
 *  - 每个客户端连接：创建一条新线程（ClientHandler），持续读取该客户端发送的消息
 *
 * 账号体系：用户注册信息保存在 MySQL 的 lanchat.Users 表中（username 主键，password 字段），
 * 登录由服务器验证，离线用户列表（注册过但不在线）也从数据库读取。
 *
 * 简单文本协议（每行一条消息，UTF-8 编码）：
 *  客户端 -> 服务端：
 *    REGISTER:用户名:密码  注册新账号（用户名不能含冒号或逗号）
 *    LOGIN:用户名:密码     登录（验证通过后才算进入聊天室）
 *    MSG:消息内容          发送公共聊天消息
 *    KICK:用户名           管理员踢人（仅管理员有效）
 *    BAN:用户名            管理员封禁并从数据库删除用户（仅管理员有效）
 *    PM:对方:消息内容       发送私聊（对方在线或离线都可以发）
 *    PMHIST:对方           拉取与某人的私聊历史（服务端发完历史后标记已读）
 *    PMREAD:对方           标记与某人的私聊为已读（私聊窗口开着时收到消息后发）
 *    CLEARPM:对方          清空自己与某人的私聊记录（不影响对方那份）
 *    CLEARPUBLIC           清空公共聊天记录（仅管理员有效）
 *    HIST                  拉取公共聊天历史
 *    UNREAD                拉取未读私聊汇总
 *    LOGOUT                主动退出
 *  服务端 -> 客户端：
 *    REGISTEROK / REGISTERFAIL:原因
 *    LOGINOK:角色           登录成功，角色为 admin / user
 *    LOGINFAIL:原因
 *    KICKED:原因            本连接被管理员踢出（随后服务端会关闭连接）
 *    BANNED:原因            本连接被管理员封禁（随后服务端会关闭连接）
 *    SYSTEM:系统消息         通知类消息（有人加入/离开/被踢等）
 *    MSG:昵称:时间戳:内容    公共聊天消息
 *    USERS:昵称1,昵称2       当前在线用户列表
 *    OFFLINEUSERS:昵称1,昵称2 已注册但不在线的用户列表
 *    HISTBEGIN / HISTITEM:昵称:时间戳:内容 / HISTEND        公共历史回放
 *    PMMSG:对方:发送者:时间戳:未读数:内容                     私聊消息实时投递
 *    PMHISTBEGIN:对方 / PMHISTITEM:对方:发送者:时间戳:内容 / PMHISTEND:对方
 *    UNREAD:对方:数量,对方2:数量2                            未读汇总
 *    PUBLICCLEARED:操作者    公共记录已被管理员清空
 *    PMCLEARED:对方          自己与某人的私聊记录已清空
 *    PMFAIL:对方:原因        私聊操作失败
 *
 * 协议约定：
 *  - 时间戳一律用 epoch millis（纯数字）。若用 HH:mm:ss 会引入冒号，破坏字段分隔。
 *  - 消息内容固定放在最后一个字段，前面的字段用连续 indexOf(':') 切分。用户名已由
 *    isValidName 禁止包含冒号和逗号，时间戳和计数是纯数字，因此切分是安全的。
 *  - 所有内容型消息在入库和广播之前必须经过 sanitize() 清洗，见该方法的注释。
 */
public class chatServer {

    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_DB_PORT = 3306;

    /** 服务器在线人数上限 */
    private static final int MAX_USERS = 100;

    /** 单条消息内容长度上限（字符数），超出部分截断 */
    private static final int MAX_MSG_LEN = 2000;

    /** 在线客户端表：昵称 -> 对应的客户端处理线程（含输出流），线程安全 */
    private static final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();

    /** 用户数据库（MySQL），所有客户端线程共享 */
    private static dbManager db;

    public static void main(String[] args) {
        // 配置优先级：命令行参数 > config.properties > 默认值
        int port = DEFAULT_PORT;
        String dbHost = "localhost";
        int dbPort = DEFAULT_DB_PORT;
        String dbUser = "root";
        String dbPass = "";

        // 从项目根目录的 config.properties 读取数据库配置
        Properties dbProps = loadConfigFile();
        if (dbProps != null) {
            dbHost = dbProps.getProperty("db.host", dbHost);
            dbPort = Integer.parseInt(dbProps.getProperty("db.port", String.valueOf(dbPort)));
            dbUser = dbProps.getProperty("db.user", dbUser);
            dbPass = dbProps.getProperty("db.password", dbPass);
        }

        // 命令行参数覆盖配置文件（用法：chatServer [端口] [数据库主机] [数据库端口] [数据库用户名] [数据库密码]）
        try {
            if (args.length >= 1) port = Integer.parseInt(args[0]);
            if (args.length >= 2) dbHost = args[1];
            if (args.length >= 3) dbPort = Integer.parseInt(args[2]);
            if (args.length >= 4) dbUser = args[3];
            if (args.length >= 5) dbPass = args[4];
        } catch (NumberFormatException e) {
            System.out.println("参数格式错误，用法：chatServer [端口] [数据库主机] [数据库端口] [数据库用户名] [数据库密码]");
        }

        // 连接数据库（自动建库建表）
        try {
            db = new dbManager(dbHost, dbPort, dbUser, dbPass);
            System.out.println("已连接数据库 " + dbHost + ":" + dbPort + "/lanchat");
        } catch (SQLException e) {
            System.err.println("数据库连接失败（" + e.getMessage() + "），服务器启动中止");
            System.err.println("请检查项目根目录的 config.properties 中的数据库账号密码，");
            System.err.println("或用启动参数指定：chatServer [端口] [数据库主机] [数据库端口] [数据库用户名] [数据库密码]");
            return;
        }

        // ===== 主线程：只负责接收客户端连接 =====
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("======== 局域网聊天室服务器已启动 ========");
            System.out.println("服务器地址：" + InetAddress.getLocalHost().getHostAddress() + "，端口：" + port);
            System.out.println("等待客户端连接...");

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("[" + time() + "] 客户端接入：" + socket.getInetAddress().getHostAddress() + ":" + socket.getPort());
                // 为每个客户端创建一条新线程，负责读取该客户端发送的消息
                new Thread(new ClientHandler(socket), "client-" + socket.getPort()).start();
            }
        } catch (IOException e) {
            System.err.println("服务器启动失败或运行异常：" + e.getMessage());
        }
    }

    /**
     * 从项目根目录的 config.properties 读取数据库配置。
     * 文件不存在或读取失败时返回 null（继续用默认值）。
     * 该文件包含真实密码，已被 .gitignore 排除，不会提交到仓库。
     */
    private static Properties loadConfigFile() {
        Path cfg = Paths.get("config.properties");
        if (!Files.exists(cfg)) {
            return null;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(cfg)) {
            props.load(in);
            return props;
        } catch (Exception e) {
            System.out.println("读取 config.properties 失败：" + e.getMessage() + "，使用默认配置");
            return null;
        }
    }

    /** 昵称/用户名合法性：非空且不含协议保留字符（冒号分隔字段，逗号分隔列表） */
    private static boolean isValidName(String name) {
        return !name.isEmpty() && !name.contains(":") && !name.contains(",");
    }

    /**
     * 清洗消息内容：限长、去首尾空白、剔除换行符。所有内容型命令（MSG / PM）
     * 在入库和广播之前都必须过这道关。
     *
     * 限长是这里的主要职责：readLine() 会无限缓冲直到遇到行结束符，手工构造的客户端
     * 可以发一行几十兆的内容把服务端内存撑爆，而 content 列是 TEXT（上限 64KB），
     * 超长内容写库也会失败。
     *
     * 换行符的剔除在当前传输方式下是冗余的——readLine() 把 \r、\n、\r\n 都当作行
     * 结束符，它返回的字符串里不可能含这两个字符。保留这行是防御性的：一旦以后换成
     * 别的分帧方式（比如带长度前缀的协议），这里就是唯一的收口。
     *
     * 真正防住换行注入的是客户端 chatClient.sendLine 里的同名清洗，原因是那条攻击
     * 路径出在发送侧：本协议「每行一条命令」，而 Swing 的 JTextField 只把 \n 过滤成
     * 空格（setDocument 里设了 filterNewlines），\r 会原样留在文本里。于是从 Windows
     * 文本（CRLF 换行）复制一段话粘进输入框再发出去，服务端 readLine 就会在 \r 处
     * 断行，把后半段当成一条独立命令执行：
     *
     *     用户粘贴 "你好\r\nCLEARPUBLIC"
     *     经 JTextField 变成 "你好\r CLEARPUBLIC"
     *     PrintWriter 原样发出，服务端 readLine 得到两行：MSG:你好  和  CLEARPUBLIC
     *
     * 发送者若恰好是管理员，一次粘贴就清空了公共聊天记录；KICK、BAN、LOGOUT 同理。
     * 注意这不是「客户端校验就够了」的反例——直接构造 socket 的攻击者本来就能发送
     * 任意命令，无需注入，挡住他们的是各命令自身的 isAdmin() 权限校验。
     */
    private static String sanitize(String content) {
        String s = content.replace('\r', ' ').replace('\n', ' ').trim();
        return s.length() > MAX_MSG_LEN ? s.substring(0, MAX_MSG_LEN) : s;
    }

    /**
     * 每个客户端连接对应一个 ClientHandler 线程，
     * 在该线程中循环读取客户端发来的每一行消息。
     */
    private static class ClientHandler implements Runnable {

        private final Socket socket;
        private String nickname;
        private String role; // 登录后从数据库读取：admin 管理员 / user 普通用户
        private BufferedReader in;
        private PrintWriter out;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                // 显式指定 UTF-8，保证中文和 emoji 不乱码
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                String line;
                // readLine() 返回 null 表示客户端已断开连接
                while ((line = in.readLine()) != null) {
                    handleMessage(line.trim());
                }
            } catch (IOException e) {
                // 客户端异常断开（如直接关闭窗口），交由 finally 统一清理
            } finally {
                disconnect();
            }
        }

        /**
         * 解析并处理客户端发来的每一条消息。
         *
         * 除注册和登录外，所有命令都必须先确认已登录（nickname != null），
         * 否则未登录的连接就能直接触发数据库查询。
         */
        private void handleMessage(String line) {
            if (line.startsWith("REGISTER:")) {
                handleRegister(line.substring("REGISTER:".length()));
                return;
            }
            if (line.startsWith("LOGIN:")) {
                handleLogin(line.substring("LOGIN:".length()));
                return;
            }
            if (nickname == null) {
                return; // 尚未登录成功，忽略后续所有命令
            }

            if (line.startsWith("MSG:")) {
                handlePublicMessage(line.substring("MSG:".length()));
            } else if (line.startsWith("PMHIST:")) {
                handlePmHistory(line.substring("PMHIST:".length()).trim());
            } else if (line.startsWith("PMREAD:")) {
                handlePmRead(line.substring("PMREAD:".length()).trim());
            } else if (line.startsWith("PM:")) {
                handlePrivateMessage(line.substring("PM:".length()));
            } else if (line.startsWith("CLEARPM:")) {
                handleClearPm(line.substring("CLEARPM:".length()).trim());
            } else if (line.equals("CLEARPUBLIC")) {
                handleClearPublic();
            } else if (line.equals("HIST")) {
                handlePublicHistory();
            } else if (line.equals("UNREAD")) {
                handleUnread();
            } else if (line.startsWith("KICK:")) {
                handleKick(line.substring("KICK:".length()).trim());
            } else if (line.startsWith("BAN:")) {
                handleBan(line.substring("BAN:".length()).trim());
            } else if (line.equals("LOGOUT")) {
                disconnect(); // 主动退出，关闭连接后读取循环自然结束
            }
        }

        /**
         * 公共聊天消息：先入库再广播。
         *
         * 入库失败时不广播（fail-closed），保证「历史里有的都广播过，广播过的都在历史里」
         * 这个不变式——否则重登后会看到一份和当时界面对不上的记录。
         */
        private void handlePublicMessage(String rest) {
            String content = sanitize(rest);
            if (content.isEmpty()) {
                return;
            }
            long ts;
            try {
                ts = db.savePublicMessage(nickname, content);
            } catch (SQLException e) {
                System.err.println("保存公共消息失败：" + e.getMessage());
                sendTo(this, "SYSTEM:消息发送失败，请稍后重试");
                return;
            }
            broadcast("MSG:" + nickname + ":" + ts + ":" + content);
            log(nickname + " 说：" + content);
        }

        /** 当前连接是否为管理员 */
        private boolean isAdmin() {
            return "admin".equals(role);
        }

        /** 管理员踢人：KICK:用户名（将目标用户从当前服务器断开） */
        private void handleKick(String target) {
            if (!isAdmin()) {
                sendTo(this, "SYSTEM:无权限执行此操作");
                return;
            }
            ClientHandler victim = clients.get(target);
            if (victim == null) {
                sendTo(this, "SYSTEM:用户「" + target + "」不在线");
                return;
            }
            if (victim == this) {
                sendTo(this, "SYSTEM:不能移除自己");
                return;
            }
            if (victim.isAdmin()) {
                sendTo(this, "SYSTEM:不能移除管理员");
                return;
            }
            sendTo(victim, "KICKED:您已被管理员「" + nickname + "」踢出聊天室");
            broadcast("SYSTEM:" + target + " 已被管理员 " + nickname + " 踢出聊天室");
            log(nickname + " 将 " + target + " 踢出聊天室");
            victim.disconnect(); // 从在线表移除并广播离开
        }

        /** 管理员封禁：BAN:用户名（从数据库删除该用户所有数据，并断开其连接） */
        private void handleBan(String target) {
            if (!isAdmin()) {
                sendTo(this, "SYSTEM:无权限执行此操作");
                return;
            }
            if (target.equals(nickname)) {
                sendTo(this, "SYSTEM:不能封禁自己");
                return;
            }
            try {
                // 事务：私聊记录和账号一起删，避免留下清不掉的孤儿行
                boolean deleted = db.banUser(target);
                if (!deleted) {
                    sendTo(this, "SYSTEM:用户「" + target + "」不存在");
                    return;
                }
            } catch (SQLException e) {
                System.err.println("封禁失败：" + e.getMessage());
                sendTo(this, "SYSTEM:封禁失败，请稍后再试");
                return;
            }
            ClientHandler victim = clients.get(target);
            if (victim != null) {
                sendTo(victim, "BANNED:您已被管理员「" + nickname + "」封禁，账号已删除");
                victim.disconnect();
            }
            broadcast("SYSTEM:" + target + " 已被管理员 " + nickname + " 封禁（账号已删除）");
            broadcastOfflineUsers(); // 数据库变化，刷新离线用户列表
            log(nickname + " 封禁并删除用户 " + target);
        }

        // ===== 私聊 =====

        /**
         * 私聊目标合法性校验，不合法时回 PMFAIL 并返回 false。
         *
         * 禁止给自己发私聊：双份行模型下 owner 和 peer 都会是自己，PMHIST 查询
         * 「owner=我 AND peer=我」会让每条消息命中两行，历史里全部重复显示。
         * 界面上右键菜单对自己不弹出（clientChatUI.showUserPopup），但手工构造
         * PM:自己:内容 能绕过，所以服务端必须自己挡住。
         */
        private boolean checkPeer(String target) {
            if (target.isEmpty() || !isValidName(target)) {
                sendTo(this, "PMFAIL:" + target + ":用户名不合法");
                return false;
            }
            if (target.equals(nickname)) {
                sendTo(this, "PMFAIL:" + target + ":不能给自己发私聊");
                return false;
            }
            try {
                if (!db.userExists(target)) {
                    sendTo(this, "PMFAIL:" + target + ":用户「" + target + "」不存在或已注销");
                    return false;
                }
            } catch (SQLException e) {
                System.err.println("校验私聊对象失败：" + e.getMessage());
                sendTo(this, "PMFAIL:" + target + ":服务器内部错误");
                return false;
            }
            return true;
        }

        /**
         * 发送私聊：PM:对方:内容
         *
         * 对方离线时消息仍然入库，等对方上线拉历史时能看到；这里额外给发送方回一条
         * 系统提示，否则发送方无法区分「已实时送达」和「石沉大海」。
         */
        private void handlePrivateMessage(String rest) {
            int colon = rest.indexOf(':');
            if (colon < 0) {
                sendTo(this, "PMFAIL::私聊格式错误");
                return;
            }
            String target = rest.substring(0, colon).trim();
            String content = sanitize(rest.substring(colon + 1));
            if (content.isEmpty()) {
                return;
            }
            if (!checkPeer(target)) {
                return;
            }

            long ts;
            try {
                ts = db.savePrivateMessage(nickname, target, content);
            } catch (SQLException e) {
                System.err.println("保存私聊消息失败：" + e.getMessage());
                sendTo(this, "PMFAIL:" + target + ":发送失败，请稍后重试");
                return;
            }

            // 回显给发送者：会话对方是 target，自己的副本没有未读概念，计数固定 0
            sendTo(this, "PMMSG:" + target + ":" + nickname + ":" + ts + ":0:" + content);

            ClientHandler peer = clients.get(target);
            if (peer != null) {
                // 投递给接收者：会话对方是发送者。未读数取服务端权威值，
                // 客户端直接显示这个数而不做本地自增，避免与 UNREAD 汇总互相覆盖。
                int unread;
                try {
                    unread = db.countUnread(target, nickname);
                } catch (SQLException e) {
                    unread = 0;
                }
                sendTo(peer, "PMMSG:" + nickname + ":" + nickname + ":" + ts + ":" + unread + ":" + content);
            } else {
                sendTo(this, "SYSTEM:「" + target + "」当前离线，消息将在其上线后送达");
            }
            log(nickname + " 私聊 " + target + "：" + content);
        }

        /**
         * 拉取私聊历史：PMHIST:对方
         *
         * 顺序必须是「先查历史发出去，再标记已读」。若反过来先标记已读，
         * 在 UPDATE 之后 SELECT 之前插入的新消息会被查出来显示，但它的 is_read 仍是 0，
         * 下次登录就会出现幽灵未读。
         */
        private void handlePmHistory(String target) {
            if (!checkPeer(target)) {
                // 即使失败也要给出结束标记，否则客户端窗口永远卡在加载中
                sendTo(this, "PMHISTBEGIN:" + target);
                sendTo(this, "PMHISTEND:" + target);
                return;
            }
            List<dbManager.ChatRecord> history;
            try {
                history = db.getPrivateHistory(nickname, target, dbManager.PRIVATE_HISTORY_LIMIT);
            } catch (SQLException e) {
                System.err.println("读取私聊历史失败：" + e.getMessage());
                sendTo(this, "PMHISTBEGIN:" + target);
                sendTo(this, "PMHISTEND:" + target);
                return;
            }
            sendTo(this, "PMHISTBEGIN:" + target);
            for (dbManager.ChatRecord r : history) {
                sendTo(this, "PMHISTITEM:" + target + ":" + r.sender + ":" + r.timestamp + ":" + r.content);
            }
            sendTo(this, "PMHISTEND:" + target);

            try {
                db.markPrivateRead(nickname, target);
            } catch (SQLException e) {
                System.err.println("标记私聊已读失败：" + e.getMessage());
            }
        }

        /** 标记已读：PMREAD:对方（私聊窗口开着时收到新消息后由客户端发来） */
        private void handlePmRead(String target) {
            if (target.isEmpty() || target.equals(nickname)) {
                return;
            }
            try {
                db.markPrivateRead(nickname, target);
            } catch (SQLException e) {
                System.err.println("标记私聊已读失败：" + e.getMessage());
            }
        }

        /**
         * 清空自己与某人的私聊记录：CLEARPM:对方
         * 只删自己那份，对方持有的副本不受影响。
         */
        private void handleClearPm(String target) {
            if (target.isEmpty() || target.equals(nickname)) {
                return;
            }
            try {
                int n = db.clearPrivateHistory(nickname, target);
                sendTo(this, "PMCLEARED:" + target);
                log(nickname + " 清空了与 " + target + " 的私聊记录（" + n + " 条）");
            } catch (SQLException e) {
                System.err.println("清空私聊记录失败：" + e.getMessage());
                sendTo(this, "PMFAIL:" + target + ":清空失败，请稍后重试");
            }
        }

        // ===== 历史与未读 =====

        /** 拉取公共聊天历史：HIST */
        private void handlePublicHistory() {
            List<dbManager.ChatRecord> history;
            try {
                history = db.getPublicHistory(dbManager.PUBLIC_HISTORY_LIMIT);
            } catch (SQLException e) {
                System.err.println("读取公共历史失败：" + e.getMessage());
                // 仍然要发出结束标记，客户端才会退出加载状态
                sendTo(this, "HISTBEGIN");
                sendTo(this, "HISTEND");
                return;
            }
            sendTo(this, "HISTBEGIN");
            for (dbManager.ChatRecord r : history) {
                sendTo(this, "HISTITEM:" + r.sender + ":" + r.timestamp + ":" + r.content);
            }
            sendTo(this, "HISTEND");
        }

        /** 拉取未读私聊汇总：UNREAD */
        private void handleUnread() {
            try {
                Map<String, Integer> summary = db.getUnreadSummary(nickname);
                StringBuilder sb = new StringBuilder("UNREAD:");
                boolean first = true;
                for (Map.Entry<String, Integer> e : summary.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(e.getKey()).append(':').append(e.getValue());
                    first = false;
                }
                sendTo(this, sb.toString());
            } catch (SQLException e) {
                System.err.println("读取未读汇总失败：" + e.getMessage());
            }
        }

        /** 管理员清空公共聊天记录：CLEARPUBLIC */
        private void handleClearPublic() {
            if (!isAdmin()) {
                sendTo(this, "SYSTEM:无权限执行此操作");
                return;
            }
            try {
                int n = db.clearPublicMessages();
                broadcast("PUBLICCLEARED:" + nickname);
                log(nickname + " 清空了公共聊天记录（" + n + " 条）");
            } catch (SQLException e) {
                System.err.println("清空公共记录失败：" + e.getMessage());
                sendTo(this, "SYSTEM:清空失败，请稍后重试");
            }
        }

        /** 注册：REGISTER:用户名:密码 */
        private void handleRegister(String rest) {
            int colon = rest.indexOf(':');
            if (colon < 0) {
                sendTo(this, "REGISTERFAIL:注册信息不完整");
                return;
            }
            String username = rest.substring(0, colon).trim();
            String password = rest.substring(colon + 1).trim();
            if (!isValidName(username) || password.isEmpty()) {
                sendTo(this, "REGISTERFAIL:用户名不能包含冒号或逗号，密码不能为空");
                return;
            }
            try {
                if (db.register(username, password)) {
                    sendTo(this, "REGISTEROK");
                    log("新用户注册：" + username);
                } else {
                    sendTo(this, "REGISTERFAIL:用户名「" + username + "」已存在");
                }
            } catch (SQLException e) {
                System.err.println("注册失败：" + e.getMessage());
                sendTo(this, "REGISTERFAIL:服务器内部错误，请稍后再试");
            }
        }

        /** 登录：LOGIN:用户名:密码，验证通过后才进入聊天室 */
        private void handleLogin(String rest) {
            int colon = rest.indexOf(':');
            if (colon < 0) {
                sendTo(this, "LOGINFAIL:登录信息不完整");
                return;
            }
            String username = rest.substring(0, colon).trim();
            String password = rest.substring(colon + 1).trim();
            if (!isValidName(username) || password.isEmpty()) {
                sendTo(this, "LOGINFAIL:用户名不能包含冒号或逗号，密码不能为空");
                return;
            }
            try {
                if (!db.userExists(username)) {
                    sendTo(this, "LOGINFAIL:用户名不存在");
                    return;
                }
                if (!db.verify(username, password)) {
                    sendTo(this, "LOGINFAIL:密码错误");
                    return;
                }
                // 在线人数上限（只统计已登录用户）
                if (clients.size() >= MAX_USERS) {
                    sendTo(this, "LOGINFAIL:服务器人数已满（最多" + MAX_USERS + "人），请稍后再试");
                    return;
                }
                // putIfAbsent 原子操作：同一账号不能在多个客户端同时登录
                if (clients.putIfAbsent(username, this) != null) {
                    sendTo(this, "LOGINFAIL:该账号已在其他客户端登录");
                    return;
                }
                this.nickname = username;
                this.role = db.getRole(username); // 登录时查询角色
                sendTo(this, "LOGINOK:" + role);
                broadcast("SYSTEM:" + nickname + " 加入了聊天室");
                broadcastUserList();
                broadcastOfflineUsers();
                log(nickname + " 登录成功，当前在线 " + clients.size() + " 人");
            } catch (SQLException e) {
                System.err.println("登录失败：" + e.getMessage());
                sendTo(this, "LOGINFAIL:服务器内部错误，请稍后再试");
            }
        }

        /**
         * 客户端断开清理（幂等，重复调用安全）。
         * 从在线表移除 -> 通知其他人 -> 关闭流和 socket
         */
        private void disconnect() {
            if (nickname != null) {
                clients.remove(nickname, this);
                broadcast("SYSTEM:" + nickname + " 离开了聊天室");
                broadcastUserList();
                broadcastOfflineUsers(); // 用户下线后离线列表变化
                log(nickname + " 已断开连接，当前在线 " + clients.size() + " 人");
                nickname = null; // 防止重复执行清理和广播
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 向该客户端发送一行消息 */
    private static void sendTo(ClientHandler client, String message) {
        client.out.println(message);
        if (client.out.checkError()) {
            // 写入失败说明该客户端连接已断开，清理掉（ConcurrentHashMap 遍历中删除是安全的）
            client.disconnect();
        }
    }

    /** 广播消息给所有在线客户端 */
    private static void broadcast(String message) {
        for (ClientHandler client : clients.values()) {
            sendTo(client, message);
        }
    }

    /** 向所有客户端广播当前在线用户列表 */
    private static void broadcastUserList() {
        broadcast("USERS:" + String.join(",", clients.keySet()));
    }

    /** 向所有客户端广播离线用户列表（已注册但不在线的用户，从数据库读取） */
    private static void broadcastOfflineUsers() {
        try {
            List<String> all = db.getAllUsernames();
            List<String> offline = new ArrayList<>();
            for (String name : all) {
                if (!clients.containsKey(name)) {
                    offline.add(name);
                }
            }
            broadcast("OFFLINEUSERS:" + String.join(",", offline));
        } catch (SQLException e) {
            System.err.println("读取离线用户列表失败：" + e.getMessage());
        }
    }

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private static String time() {
        return TIME_FORMAT.format(new Date());
    }

    private static void log(String msg) {
        System.out.println("[" + time() + "] " + msg);
    }
}
