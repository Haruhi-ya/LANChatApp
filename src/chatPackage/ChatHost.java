package chatPackage;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ChatHost {

}











class Server {

    // ==================== 全局数据区 ====================
    // 存放所有客户端的输出流，用于广播消息
    private static List<PrintWriter> allClients = new ArrayList<>();

    // ==================== 主启动方法 ====================
    public static void main(String[] args) {
        System.out.println("========== 聊天室服务端启动 ==========");
        // 启动一个单独的线程，用于读取服务端自身的控制台输入并广播
        new Thread(new ServerConsoleInput()).start();

        try (ServerSocket serverSocket = new ServerSocket(8888)) {
            while (true) {
                // 阻塞等待客户端连接
                Socket clientSocket = serverSocket.accept();
                System.out.println("[连接] 新客户端接入：" + clientSocket.getRemoteSocketAddress());
                // 每个客户端分配一个独立线程处理
                new ClientHandler(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ==================== 广播工具方法 ====================
    // 将消息发送给所有在线客户端
    private static void broadcast(String message) {
        synchronized (allClients) {
            // 遍历所有输出流，发送消息
            Iterator<PrintWriter> it = allClients.iterator();
            while (it.hasNext()) {
                PrintWriter writer = it.next();
                writer.println(message);
                // 如果发送失败（客户端已断开），移除该输出流
                if (writer.checkError()) {
                    it.remove();
                }
            }
        }
    }

    // ==================== 服务端控制台输入线程 ====================
    // 功能：读取服务端自己输入的内容，并广播给所有人（类似管理员消息）
    static class ServerConsoleInput implements Runnable {
        @Override
        public void run() {
            try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = consoleReader.readLine()) != null) {
                    // 服务端消息加上前缀【管理员】
                    broadcast("【管理员】" + line);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // ==================== 客户端处理线程（一对一） ====================
    // 每个客户端连接对应一个实例，负责接收该客户端消息并广播
    static class ClientHandler extends Thread {
        private Socket socket;
        private PrintWriter out;
        private String clientAddress; // 用于标识

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.clientAddress = socket.getRemoteSocketAddress().toString();
        }

        @Override
        public void run() {
            try (
                    // 获取该客户端的输入流
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    // 获取该客户端的输出流
                    PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
            ) {
                this.out = out;

                // 将本客户端的输出流加入全局列表，以便接收广播
                synchronized (allClients) {
                    allClients.add(out);
                }

                // 通知所有人有新用户加入
                broadcast("【系统】" + clientAddress + " 进入了聊天室");

                // 持续读取该客户端发来的消息
                String msg;
                while ((msg = in.readLine()) != null) {
                    // ===== 新增：服务端控制台显示收到的消息 =====
                    System.out.println("[来自 " + clientAddress + "] " + msg);

                    // 继续广播给所有客户端
                    broadcast(clientAddress + "：" + msg);
                }

            } catch (IOException e) {
                // 客户端异常断开，记录日志
                System.err.println(clientAddress + " 连接异常：" + e.getMessage());
            } finally {
                // 客户端正常或异常退出，清理资源
                synchronized (allClients) {
                    allClients.remove(out);
                }
                broadcast("【系统】" + clientAddress + " 离开了聊天室");
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }
}