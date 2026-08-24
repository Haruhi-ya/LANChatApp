package chatPackage;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ChatPartner {

}


class Client {

    // ==================== 主启动方法 ====================
    public static void main(String[] args) {
        System.out.println("========== 聊天室客户端 ==========");
        // 可修改为服务端实际 IP（本机测试用 127.0.0.1）
        String host = "127.0.0.1";
        int port = 8888;

        try (Socket socket = new Socket(host, port)) {
            System.out.println("已连接到服务器：" + host + ":" + port);

            // ======== 1. 启动副线程（接收广播） ========
            // 该线程持续读取服务端发来的消息并打印
            Thread receiverThread = new Thread(new IncomingReader(socket));
            receiverThread.setDaemon(true); // 当主线程结束时，副线程自动结束
            receiverThread.start();

            // ======== 2. 主线程负责发送消息 ========
            // 获取输出流，用于向服务端发送消息
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            // 读取用户控制台输入
            BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in));

            String userInput;
            System.out.println("提示：输入消息按回车发送，输入 'exit' 退出");
            while ((userInput = consoleReader.readLine()) != null) {
                if ("exit".equalsIgnoreCase(userInput.trim())) {
                    break; // 退出循环，关闭客户端
                }
                // 将用户输入发送给服务端
                out.println(userInput);
            }

        } catch (IOException e) {
            System.err.println("连接服务器失败：" + e.getMessage());
        }
        System.out.println("客户端已退出");
    }

    // ==================== 副线程：接收服务器广播 ====================
    static class IncomingReader implements Runnable {
        private BufferedReader in;

        public IncomingReader(Socket socket) throws IOException {
            // 获取输入流，用于读取服务端广播的消息
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        }

        @Override
        public void run() {
            String message;
            try {
                while ((message = in.readLine()) != null) {
                    // 接收到服务端的广播，直接打印到控制台
                    System.out.println(message);
                }
            } catch (IOException e) {
                // 如果服务端断开，readLine()会抛异常，或返回null
                System.out.println("与服务器的连接已断开");
            }
        }
    }
}