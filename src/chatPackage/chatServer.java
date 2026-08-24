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
 *    MSG:消息内容          发送聊天消息
 *    LOGOUT                主动退出
 *  服务端 -> 客户端：
 *    REGISTEROK / REGISTERFAIL:原因
 *    LOGINOK / LOGINFAIL:原因
 *    SYSTEM:系统消息         通知类消息（有人加入/离开等）
 *    MSG:昵称:消息内容       普通聊天消息（消息体取第一个冒号之后的部分）
 *    USERS:昵称1,昵称2       当前在线用户列表
 *    OFFLINEUSERS:昵称1,昵称2 已注册但不在线的用户列表
 */
public class chatServer {

    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_DB_PORT = 3306;

    /** 服务器在线人数上限 */
    private static final int MAX_USERS = 100;

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
     * 每个客户端连接对应一个 ClientHandler 线程，
     * 在该线程中循环读取客户端发来的每一行消息。
     */
    private static class ClientHandler implements Runnable {

        private final Socket socket;
        private String nickname;
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

        /** 解析并处理客户端发来的每一条消息 */
        private void handleMessage(String line) {
            if (line.startsWith("REGISTER:")) {
                handleRegister(line.substring("REGISTER:".length()));
            } else if (line.startsWith("LOGIN:")) {
                handleLogin(line.substring("LOGIN:".length()));
            } else if (line.startsWith("MSG:")) {
                if (nickname == null) {
                    return; // 尚未登录成功，忽略消息
                }
                String content = line.substring("MSG:".length()).trim();
                if (!content.isEmpty()) {
                    broadcast("MSG:" + nickname + ":" + content);
                    log(nickname + " 说：" + content);
                }
            } else if (line.equals("LOGOUT")) {
                disconnect(); // 主动退出，关闭连接后读取循环自然结束
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
                sendTo(this, "LOGINOK");
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
