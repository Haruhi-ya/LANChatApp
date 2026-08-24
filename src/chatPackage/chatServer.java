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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天室服务器
 *
 * 线程模型：
 *  - 主线程：ServerSocket.accept() 循环，只负责接收客户端的 socket 连接
 *  - 每个客户端连接：创建一条新线程（ClientHandler），持续读取该客户端发送的消息
 *
 * 简单文本协议（每行一条消息，UTF-8 编码）：
 *  客户端 -> 服务端：
 *    LOGIN:昵称       登录（昵称不能与在线用户重复）
 *    MSG:消息内容     发送聊天消息
 *    LOGOUT           主动退出
 *  服务端 -> 客户端：
 *    SYSTEM:系统消息     通知类消息（有人加入/离开等）
 *    MSG:昵称:消息内容   普通聊天消息（UTF-8 时内容中的冒号不影响解析，
 *                        因为读取方按第一个冒号之后的所有内容为消息体）
 *    USERS:昵称1,昵称2  当前在线用户列表
 */
public class chatServer {

    private static final int DEFAULT_PORT = 8080;

    /** 在线客户端表：昵称 -> 对应的客户端处理线程（含输出流），线程安全 */
    private static final ConcurrentHashMap<String, ClientHandler> clients = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("端口号格式错误，使用默认端口 " + DEFAULT_PORT);
            }
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
            if (line.startsWith("LOGIN:")) {
                login(line.substring("LOGIN:".length()).trim());
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

        /** 处理客户端登录 */
        private void login(String name) {
            // 昵称不能含协议保留字符：冒号分隔昵称与内容，逗号分隔用户列表
            if (name.isEmpty() || name.contains(":") || name.contains(",")) {
                sendTo(this, "SYSTEM:昵称不能包含冒号（:）或逗号（,）");
                return; // 提示后继续等待客户端重新登录
            }
            // putIfAbsent 原子操作：同一昵称同时登录时只有一个能成功
            if (clients.putIfAbsent(name, this) != null) {
                sendTo(this, "SYSTEM:昵称「" + name + "」已被占用，请更换昵称");
                disconnect();
                return;
            }
            this.nickname = name;
            broadcast("SYSTEM:" + name + " 加入了聊天室");
            broadcastUserList();
            log(name + " 加入聊天室，当前在线 " + clients.size() + " 人");
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

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    private static String time() {
        return TIME_FORMAT.format(new Date());
    }

    private static void log(String msg) {
        System.out.println("[" + time() + "] " + msg);
    }
}
