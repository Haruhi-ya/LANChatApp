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
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天室服务器
 *
 * 线程模型：
 *  - 主线程：ServerSocket.accept() 循环，只负责接收客户端连接
 *  - 每个客户端连接：起一条新线程（ClientHandler），循环读取该客户端发来的消息
 *
 * 账号体系：用户信息存在 MySQL 的 lanchat.Users 表（username 主键），
 * 登录由服务器验证，离线用户列表（注册过但不在线）也从数据库读取。
 *
 * 通信协议：自定义的简单文本协议，一行一条命令，UTF-8 编码，冒号分隔字段：
 *  客户端 -> 服务端：
 *    REGISTER:用户名:密码   注册
 *    LOGIN:用户名:密码      登录（验证通过后才算进入聊天室）
 *    MSG:内容               发公共消息
 *    PM:对方:内容            发私聊（对方离线也入库，上线后能看到）
 *    PMHIST:对方 / PMREAD:对方 / CLEARPM:对方   拉私聊历史 / 标记已读 / 清空私聊记录
 *    HIST / UNREAD           拉公共历史 / 拉未读私聊汇总
 *    RECALL:消息ID / PMRECALL:对方:消息ID    撤回公共 / 私聊消息（只能撤自己的）
 *    KICK:用户名 / BAN:用户名 管理员踢人 / 封禁（仅管理员有效）
 *    CLEARPUBLIC             管理员清空公共聊天记录
 *    LOGOUT                  退出
 *  服务端 -> 客户端：
 *    REGISTEROK / REGISTERFAIL:原因
 *    LOGINOK:角色 / LOGINFAIL:原因       角色为 admin / user
 *    MSG:昵称:消息ID:时间戳:内容           公共消息
 *    PMMSG:对方:发送者:消息ID:时间戳:未读数:内容   私聊消息
 *    SYSTEM:内容    系统消息（有人加入/离开/被踢等）
 *    USERS:昵称列表 / OFFLINEUSERS:昵称列表   在线/离线用户列表
 *    KICKED:原因 / BANNED:原因   被管理员踢出 / 封禁（随后连接被关闭）
 *    HISTBEGIN / HISTITEM / HISTEND      公共历史回放
 *    RECALLED:消息ID:操作者 / RECALLFAIL:消息ID:原因   撤回结果
 *
 * 协议设计时注意的点：
 *  - 时间戳用 epoch 毫秒（纯数字），用 HH:mm:ss 会带冒号，破坏字段分隔
 *  - 消息内容固定放在最后一个字段，切分时用 indexOf(':') 只切前几个字段，
 *    内容里的冒号不会被切错；用户名则干脆禁止包含冒号和逗号（isValidName）
 *  - 消息ID由服务端生成（UUID），不接受客户端传的，防止伪造 ID 去撤别人的消息
 *  - 所有内容型消息入库和广播前都要过 sanitize() 清洗（去换行、限长）
 */
public class chatServer {

    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_DB_PORT = 3306;

    /** 服务器在线人数上限 */
    private static final int MAX_USERS = 100;

    /** 单条消息内容长度上限（字符数），超出部分截断 */
    private static final int MAX_MSG_LEN = 2000;

    /**
     * 图片消息内容长度上限（字符数）：Base64 膨胀 4/3，1MB 图片约 1.4M 字符。
     * 图片消息不走 MAX_MSG_LEN 截断（截断会破坏 Base64），而是整体校验后拒绝。
     */
    private static final int MAX_IMG_MSG_LEN = 1_500_000;

    /** 头像 Base64 上限（字符数）：对应 chatTheme.MAX_AVATAR_BYTES 128KB */
    private static final int MAX_AVATAR_B64_LEN = 512_000;

    /** 消息撤回时间窗口（毫秒），超过后服务端拒绝撤回 */
    private static final long RECALL_WINDOW_MS = 2 * 60 * 1000L;

    /** 搜索关键词长度上限 */
    private static final int MAX_SEARCH_KEYWORD_LEN = 100;

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
     * 读取数据库配置：优先读 jar 同目录的 config.properties，再试工作目录，都没有就用默认值。
     *
     * 这里踩过一个坑：之前只读工作目录下的配置文件，把 jar 单独拷出来用 java -jar 启动时
     * 工作目录变了，配置读不到，服务器直接连不上 MySQL。后来改成先找 jar 所在目录，
     * 这样双击 bat 启动和裸跑 jar 都能读到配置。
     * 文件不存在或读失败时返回 null（后面继续用默认值）。
     * 这个文件里是真实的数据库密码，已在 .gitignore 里排除，不会提交到仓库。
     */
    private static Properties loadConfigFile() {
        Path cfg = null;
        try {
            Path jarDir = Paths.get(chatServer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).getParent();
            if (jarDir != null) {
                Path beside = jarDir.resolve("config.properties");
                if (Files.exists(beside)) {
                    cfg = beside;
                }
            }
        } catch (Exception ignored) {
            // 拿不到 jar 位置时回退工作目录
        }
        if (cfg == null) {
            cfg = Paths.get("config.properties");
        }
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
     * 清洗消息内容：去换行、去首尾空白、限长。所有内容型命令（MSG / PM）入库和广播前都要过这道。
     *
     * 去换行是必须的：协议是一行一条命令，服务端 readLine() 把 \r、\n、\r\n 都当行结束符，
     * 内容里混进换行符就会被拆成两行，后半段被当成一条独立命令执行。实际踩到的情况是：
     * 从 Windows 文本里复制一句话粘贴进聊天输入框再发出去，输入框只过滤 \n 不过滤 \r，
     * 于是 "你好\r\nCLEARPUBLIC" 发到服务端变成了两条命令——MSG:你好 和 CLEARPUBLIC。
     * 发的人恰好是管理员的话，一次粘贴就把公共聊天记录全清了。所以发送侧（chatClient.sendLine）
     * 和接收侧（这里）各做一道清洗，两边都防。
     *
     * 限长也放在这里：readLine() 会一直缓冲到行结束，手工构造的客户端可以发一行几十兆
     * 的内容把服务器内存撑爆，而且数据库 content 列是 TEXT（上限 64KB），超长写库也会失败。
     */
    private static String sanitize(String content) {
        String s = content.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.startsWith(chatTheme.IMG_PREFIX)) {
            return sanitizeImageMessage(s);
        }
        return s.length() > MAX_MSG_LEN ? s.substring(0, MAX_MSG_LEN) : s;
    }

    /**
     * 图片消息校验：图片不能像文本那样截断，截断就破坏了 Base64，解码必失败。
     * 所以这里做整体校验：长度超限、Base64 不合法、文件头不是 JPEG/PNG（魔数）都直接拒绝，
     * 调用方按「内容为空」丢弃，不留半截数据入库。手打一段 "[IMG]hello" 也会被魔数挡住。
     */
    private static String sanitizeImageMessage(String s) {
        if (s.length() > MAX_IMG_MSG_LEN) {
            return "";
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(s.substring(chatTheme.IMG_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return "";
        }
        if (data.length < 4 || data.length > chatTheme.MAX_IMAGE_MESSAGE_BYTES) {
            return "";
        }
        // 魔数校验：JPEG 以 FFD8FF 开头，PNG 以 89504E47 开头
        boolean jpeg = (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
        boolean png = (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47;
        return (jpeg || png) ? s : "";
    }

    /** 日志摘要：图片消息只记类型与大小，避免 1.4M 字符的 Base64 刷屏 */
    private static String logSummary(String content) {
        if (content.startsWith(chatTheme.IMG_PREFIX)) {
            return "[图片] (" + (content.length() - chatTheme.IMG_PREFIX.length()) * 3 / 4 + " 字节)";
        }
        return content;
    }

    /**
     * 一个 ClientHandler 就是一个客户端的连接处理线程。
     * 每个客户端连上来，主线程就 new 一个并 start，这个线程一直循环
     * 读该客户端发来的每一行消息，直到对方断开。
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
         * 按命令前缀分发给对应的处理方法。
         * 注意除注册和登录外，其他命令都要先确认已登录（nickname != null），
         * 不然没登录的连接也能触发数据库查询，肯定不行。
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
            } else if (line.startsWith("PMRECALL:")) {
                handlePmRecall(line.substring("PMRECALL:".length()));
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
            } else if (line.startsWith("RECALL:")) {
                handleRecall(line.substring("RECALL:".length()).trim());
            } else if (line.startsWith("SEARCHPUB:")) {
                handleSearchPublic(line.substring("SEARCHPUB:".length()));
            } else if (line.startsWith("SEARCHPM:")) {
                handleSearchPrivate(line.substring("SEARCHPM:".length()));
            } else if (line.startsWith("KICK:")) {
                handleKick(line.substring("KICK:".length()).trim());
            } else if (line.startsWith("BAN:")) {
                handleBan(line.substring("BAN:".length()).trim());
            } else if (line.startsWith("GETAVATAR:")) {
                handleGetAvatar(line.substring("GETAVATAR:".length()).trim());
            } else if (line.startsWith("SETAVATAR:")) {
                handleSetAvatar(line.substring("SETAVATAR:".length()).trim());
            } else if (line.equals("LOGOUT")) {
                disconnect(); // 主动退出，关闭连接后读取循环自然结束
            }
        }

        // ===== 头像 =====

        /**
         * 拉取用户头像：GETAVATAR:用户名，响应 AVATAR:用户名:base64（没设置头像时 base64 为空）。
         * 头像按需拉取，不放进 USERS 用户列表广播里——不然每次刷新列表都要传所有人的头像，太浪费。
         */
        private void handleGetAvatar(String target) {
            if (!isValidName(target)) {
                return;
            }
            try {
                byte[] avatar = db.getAvatar(target);
                String b64 = avatar == null || avatar.length == 0
                        ? "" : Base64.getEncoder().encodeToString(avatar);
                sendTo(this, "AVATAR:" + target + ":" + b64);
            } catch (SQLException e) {
                System.err.println("读取头像失败：" + e.getMessage());
                sendTo(this, "AVATAR:" + target + ":");
            }
        }

        /**
         * 上传/移除自己的头像：SETAVATAR:base64（空 = 移除）。
         * 成功回 AVATAROK 并广播 AVATARCHG:用户名（其他客户端收到后清掉缓存，按需重新拉取）；
         * 失败回 AVATARFAIL:原因。
         */
        private void handleSetAvatar(String b64) {
            if (b64.isEmpty()) {
                try {
                    db.setAvatar(nickname, null);
                } catch (SQLException e) {
                    System.err.println("清除头像失败：" + e.getMessage());
                    sendTo(this, "AVATARFAIL:清除头像失败，请稍后再试");
                    return;
                }
                broadcast("AVATARCHG:" + nickname);
                sendTo(this, "AVATAROK");
                log(nickname + " 移除了头像");
                return;
            }
            if (b64.length() > MAX_AVATAR_B64_LEN) {
                sendTo(this, "AVATARFAIL:头像文件过大（上限 128KB）");
                return;
            }
            byte[] data;
            try {
                data = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                sendTo(this, "AVATARFAIL:头像数据格式错误");
                return;
            }
            if (data.length > chatTheme.MAX_AVATAR_BYTES) {
                sendTo(this, "AVATARFAIL:头像文件过大（上限 128KB）");
                return;
            }
            // 校验文件头（魔数）：JPEG 以 FFD8FF 开头，PNG 以 89504E47 开头，
            // 防止把任意二进制数据当头像存进数据库
            boolean jpeg = (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
            boolean png = (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47;
            if (!jpeg && !png) {
                sendTo(this, "AVATARFAIL:头像必须是 JPG 或 PNG 图片");
                return;
            }
            try {
                db.setAvatar(nickname, data);
            } catch (SQLException e) {
                System.err.println("保存头像失败：" + e.getMessage());
                sendTo(this, "AVATARFAIL:保存头像失败，请稍后再试");
                return;
            }
            broadcast("AVATARCHG:" + nickname);
            sendTo(this, "AVATAROK");
            log(nickname + " 更新了头像（" + data.length + " 字节）");
        }

        /**
         * 公共聊天消息：先存数据库，成功后再广播给所有在线客户端。
         * 入库失败就不广播。顺序不能反：如果先广播、存库却失败了，
         * 用户重登拉历史时会发现刚才明明看到的消息不见了，两边对不上。
         */
        private void handlePublicMessage(String rest) {
            String content = sanitize(rest);
            if (content.isEmpty()) {
                return;
            }
            dbManager.SavedMessage saved;
            try {
                saved = db.savePublicMessage(nickname, content);
            } catch (SQLException e) {
                System.err.println("保存公共消息失败：" + e.getMessage());
                sendTo(this, "SYSTEM:消息发送失败，请稍后重试");
                return;
            }
            broadcast("MSG:" + nickname + ":" + saved.msgId + ":" + saved.timestamp + ":" + content);
            // @提醒放在广播之后发送，保证被@的人先渲染出消息再闪窗
            notifyMentioned(content);
            log(nickname + " 说：" + logSummary(content));
        }

        /**
         * @提醒检测：扫描公共消息内容里的「@用户名」，命中的在线用户发 ATMSG 通知。
         * 同一消息 @ 同一人只提醒一次；不提醒自己；只提醒在线用户。
         *
         * 踩过的坑：判断词边界要用 Unicode 类别 \\p{L}\\p{N} 而不是 Java 的 \\w——
         * \\w 只等于 [a-zA-Z0-9_]，不认中文。用户名「张」和「张三」同时存在时，
         * 发「@张三你好」用 \\w 判断会把「@张三」当成「@张」+ 一个非词字符，误提醒给「张」。
         */
        private void notifyMentioned(String content) {
            // 图片消息是 Base64 文本，没有 @ 语义，扫几兆字符串 × 所有在线用户还会卡
            if (content.startsWith(chatTheme.IMG_PREFIX)) {
                return;
            }
            Set<String> mentioned = new HashSet<>();
            for (String name : clients.keySet()) {
                if (name.equals(nickname) || mentioned.contains(name)) {
                    continue;
                }
                Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote("@" + name)
                        + "(?![\\p{L}\\p{N}_])");
                if (p.matcher(content).find()) {
                    mentioned.add(name);
                    ClientHandler target = clients.get(name);
                    if (target != null) {
                        sendTo(target, "ATMSG:" + nickname);
                    }
                }
            }
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
         * 不能给自己发私聊：一条私聊在数据库里存两份（自己一份、对方一份），
         * 给自己发的话两份都是自己，拉历史时每条消息会命中两行，重复显示。
         * 界面上右键菜单对自己不弹出，但手工构造 PM:自己:内容 能绕过，所以服务端要挡。
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
         * 发送私聊：PM:对方:内容。
         * 对方离线时消息照常入库，等对方上线拉历史时能看到；同时给发送方回一条系统提示，
         * 不然发送方分不清消息是实时送达了还是石沉大海。
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

            dbManager.SavedMessage saved;
            try {
                saved = db.savePrivateMessage(nickname, target, content);
            } catch (SQLException e) {
                System.err.println("保存私聊消息失败：" + e.getMessage());
                sendTo(this, "PMFAIL:" + target + ":发送失败，请稍后重试");
                return;
            }

            // 回显给发送者：会话对方是 target，自己的副本没有未读概念，计数固定 0
            sendTo(this, "PMMSG:" + target + ":" + nickname + ":" + saved.msgId + ":"
                    + saved.timestamp + ":0:" + content);

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
                sendTo(peer, "PMMSG:" + nickname + ":" + nickname + ":" + saved.msgId + ":"
                        + saved.timestamp + ":" + unread + ":" + content);
            } else {
                sendTo(this, "SYSTEM:「" + target + "」当前离线，消息将在其上线后送达");
            }
            log(nickname + " 私聊 " + target + "：" + logSummary(content));
        }

        /**
         * 拉取私聊历史：PMHIST:对方。
         * 顺序必须是先发历史、再标记已读。反过来做的话，标完已读之后新来的消息
         * 会被查出来显示，但它的已读标记还是 0，下次登录会出现"明明读过了还有红点"的怪事。
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
                // 老数据没有 msgId，线里留空字段，客户端按空值判定不可撤回
                sendTo(this, "PMHISTITEM:" + target + ":" + r.sender + ":" + nullToEmpty(r.msgId)
                        + ":" + r.timestamp + ":" + r.content);
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
                sendTo(this, "HISTITEM:" + r.sender + ":" + nullToEmpty(r.msgId)
                        + ":" + r.timestamp + ":" + r.content);
            }
            sendTo(this, "HISTEND");
        }

        /** 拉取未读私聊汇总：UNREAD */
        private void handleUnread() {
            sendUnreadSummary(this);
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

        // ===== 消息撤回 =====

        /**
         * 撤回公共消息：RECALL:消息ID。
         * 权限：只能撤自己的，管理员可以撤任何人的；时限：2 分钟内（管理员豁免）。
         * 时间用服务端写入的 send_time 判断，不信任客户端传的时间。
         * 校验和删除都在 dbManager.recallPublic 的同一个 synchronized 方法里完成，
         * 先校验后删除，越权或超时的撤回不会真的删到数据。
         * 撤回即删除，离线用户上线拉历史时自然看不到这条消息。
         */
        private void handleRecall(String msgId) {
            if (!isValidMsgId(msgId)) {
                sendTo(this, "RECALLFAIL:" + msgId + ":消息ID不合法");
                return;
            }
            int result;
            try {
                result = db.recallPublic(msgId, nickname, isAdmin(), RECALL_WINDOW_MS);
            } catch (SQLException e) {
                System.err.println("撤回公共消息失败：" + e.getMessage());
                sendTo(this, "RECALLFAIL:" + msgId + ":服务器内部错误");
                return;
            }
            switch (result) {
                case dbManager.RECALL_PERMISSION_DENIED:
                    sendTo(this, "RECALLFAIL:" + msgId + ":只能撤回自己发送的消息");
                    return;
                case dbManager.RECALL_TIMEOUT:
                    sendTo(this, "RECALLFAIL:" + msgId + ":超过2分钟，无法撤回");
                    return;
                case 0:
                    sendTo(this, "RECALLFAIL:" + msgId + ":消息不存在或已被撤回");
                    return;
                default:
                    break;
            }
            broadcast("RECALLED:" + msgId + ":" + nickname);
            log(nickname + " 撤回了一条公共消息（" + msgId + "）");
        }

        /**
         * 撤回私聊消息：PMRECALL:对方:消息ID。
         * 只能撤自己的消息（管理员在私聊里没有特权）。私聊在库里是双份行，
         * 一次 DELETE 把双方的两份一起删——和微信一致，对方那边这条消息也消失。
         * 回执里的 peer 字段按数据库行的视角分别构造，不直接信任命令参数。
         */
        private void handlePmRecall(String rest) {
            int colon = rest.indexOf(':');
            if (colon < 0) {
                sendTo(this, "PMFAIL::私聊撤回格式错误");
                return;
            }
            String peerParam = rest.substring(0, colon).trim();
            String msgId = rest.substring(colon + 1).trim();
            if (!isValidMsgId(msgId)) {
                sendTo(this, "RECALLFAIL:" + msgId + ":消息ID不合法");
                return;
            }

            // 删除前先把接收方信息和未读状态查出来，删除后这些行就没了
            String senderPeer;
            boolean wasUnread;
            try {
                String[] meta = db.privateRecallMeta(msgId);
                if (meta == null) {
                    sendTo(this, "RECALLFAIL:" + msgId + ":消息不存在或已被撤回");
                    return;
                }
                senderPeer = meta[0]; // 发送者视角的会话对方（=接收者）
                wasUnread = Boolean.parseBoolean(meta[1]);
            } catch (SQLException e) {
                System.err.println("查询私聊撤回信息失败：" + e.getMessage());
                sendTo(this, "RECALLFAIL:" + msgId + ":服务器内部错误");
                return;
            }

            int result;
            try {
                result = db.recallPrivate(msgId, nickname, RECALL_WINDOW_MS);
            } catch (SQLException e) {
                System.err.println("撤回私聊消息失败：" + e.getMessage());
                sendTo(this, "RECALLFAIL:" + msgId + ":服务器内部错误");
                return;
            }
            switch (result) {
                case dbManager.RECALL_PERMISSION_DENIED:
                    sendTo(this, "RECALLFAIL:" + msgId + ":只能撤回自己发送的消息");
                    return;
                case dbManager.RECALL_TIMEOUT:
                    sendTo(this, "RECALLFAIL:" + msgId + ":超过2分钟，无法撤回");
                    return;
                case 0:
                    sendTo(this, "RECALLFAIL:" + msgId + ":消息不存在或已被撤回");
                    return;
                default:
                    break;
            }

            // 给发送者（自己）回执：peer=会话对方
            sendTo(this, "PMRECALLED:" + senderPeer + ":" + msgId + ":" + nickname);
            // 给接收者回执：peer=发送者（即撤回者本人），不信任命令里的 peer 参数
            ClientHandler receiver = clients.get(senderPeer);
            if (receiver != null) {
                sendTo(receiver, "PMRECALLED:" + nickname + ":" + msgId + ":" + nickname);
                // 撤回的是对方尚未读的消息：对方界面的未读红点还按旧快照多算 1，补发汇总纠正
                if (wasUnread) {
                    sendUnreadSummary(receiver);
                }
            }
            log(nickname + " 撤回了一条发给 " + senderPeer + " 的私聊消息");
        }

        // ===== 聊天记录搜索 =====

        /** 搜索公共聊天记录：SEARCHPUB:关键词 */
        private void handleSearchPublic(String keyword) {
            String kw = keyword.trim();
            if (kw.isEmpty()) {
                sendTo(this, "SEARCHFAIL:PUBLIC:请输入关键词");
                return;
            }
            if (kw.length() > MAX_SEARCH_KEYWORD_LEN) {
                sendTo(this, "SEARCHFAIL:PUBLIC:关键词过长");
                return;
            }
            try {
                List<dbManager.ChatRecord> results = db.searchPublic(kw, 100);
                sendTo(this, "SRESULTBEGIN:PUBLIC");
                for (dbManager.ChatRecord r : results) {
                    sendTo(this, "SRESULT:PUBLIC:" + nullToEmpty(r.msgId) + ":" + r.sender + ":"
                            + r.timestamp + ":" + r.content);
                }
                sendTo(this, "SRESULTEND:PUBLIC");
            } catch (SQLException e) {
                System.err.println("搜索公共记录失败：" + e.getMessage());
                sendTo(this, "SEARCHFAIL:PUBLIC:服务器内部错误");
            }
        }

        /** 搜索私聊记录：SEARCHPM:对方:关键词 */
        private void handleSearchPrivate(String rest) {
            int colon = rest.indexOf(':');
            if (colon < 0) {
                sendTo(this, "PMFAIL::搜索格式错误");
                return;
            }
            String target = rest.substring(0, colon).trim();
            String kw = rest.substring(colon + 1).trim();
            if (!checkPeer(target)) {
                return;
            }
            if (kw.isEmpty()) {
                sendTo(this, "SEARCHFAIL:" + target + ":请输入关键词");
                return;
            }
            if (kw.length() > MAX_SEARCH_KEYWORD_LEN) {
                sendTo(this, "SEARCHFAIL:" + target + ":关键词过长");
                return;
            }
            try {
                List<dbManager.ChatRecord> results = db.searchPrivate(nickname, target, kw, 100);
                sendTo(this, "SRESULTBEGIN:" + target);
                for (dbManager.ChatRecord r : results) {
                    sendTo(this, "SRESULT:" + target + ":" + nullToEmpty(r.msgId) + ":" + r.sender + ":"
                            + r.timestamp + ":" + r.content);
                }
                sendTo(this, "SRESULTEND:" + target);
            } catch (SQLException e) {
                System.err.println("搜索私聊记录失败：" + e.getMessage());
                sendTo(this, "SEARCHFAIL:" + target + ":服务器内部错误");
            }
        }

        /** msgId 合法性：UUID 格式（8-4-4-4-12 的 hex + 连字符），防协议垃圾 */
        private static boolean isValidMsgId(String msgId) {
            if (msgId == null || msgId.length() != 36) {
                return false;
            }
            for (int i = 0; i < 36; i++) {
                char c = msgId.charAt(i);
                if (i == 8 || i == 13 || i == 18 || i == 23) {
                    if (c != '-') {
                        return false;
                    }
                } else if (Character.digit(c, 16) < 0) {
                    return false; // 非十六进制字符
                }
            }
            return true;
        }

        private static String nullToEmpty(String s) {
            return s == null ? "" : s;
        }

        /** 把未读汇总推给指定客户端（撤回未读私聊后用来纠正红点） */
        private static void sendUnreadSummary(ClientHandler client) {
            try {
                Map<String, Integer> summary = db.getUnreadSummary(client.nickname);
                StringBuilder sb = new StringBuilder("UNREAD:");
                boolean first = true;
                for (Map.Entry<String, Integer> e : summary.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append(e.getKey()).append(':').append(e.getValue());
                    first = false;
                }
                sendTo(client, sb.toString());
            } catch (SQLException e) {
                System.err.println("推送未读汇总失败：" + e.getMessage());
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
                // putIfAbsent：判断 + 插入一步完成（原子操作），防止同一账号在多台电脑同时登录
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
         * 客户端断开后的统一清理：从在线表移除 -> 通知其他人 -> 关闭流和 socket。
         * 用 nickname 判空保证幂等，断开和异常断开同时触发时不会重复广播。
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
            // 写入失败说明对方连接已经断了，顺手清理掉（ConcurrentHashMap 遍历时删除是安全的）
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
