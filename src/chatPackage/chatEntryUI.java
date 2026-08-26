package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 程序入口：登录/注册窗口。
 * 整个程序的启动流程都在这：收集用户输入的服务器 IP、端口、账号密码，
 * 起一个后台线程去连服务器，等登录结果回来后再切换到聊天主窗口。
 */
public class chatEntryUI extends JFrame {

    private JTextField usernameField;
    private JPasswordField passwordField;
    private JTextField ipField;
    private JTextField portField;
    private JButton loginButton;
    private JButton registerButton;
    private JButton cancelButton;

    private static final Color PRIMARY = new Color(99, 132, 255);
    private static final Color PRIMARY_HOVER = new Color(75, 108, 235);
    private static final Color BG_LIGHT = new Color(245, 247, 252);
    private static final Color CARD_BG = Color.WHITE;
    private static final Color TEXT_DARK = new Color(44, 52, 74);
    private static final Color TEXT_GRAY = new Color(140, 149, 168);
    private static final Color CANCEL_BG = new Color(235, 238, 245);
    private static final Color CANCEL_HOVER = new Color(220, 224, 235);

    /** 等待服务器登录/注册响应的超时时间 */
    private static final int RESPONSE_TIMEOUT_SECONDS = 5;

    public chatEntryUI() {
        initUI();
    }

    private void initUI() {
        setTitle("局域网聊天室");
        setResizable(false);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel wrapper = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, new Color(230, 235, 255),
                        getWidth(), getHeight(), new Color(248, 240, 255));
                g2.setPaint(gp);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        wrapper.setBorder(new EmptyBorder(40, 50, 40, 50));

        JPanel card = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CARD_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(new Color(220, 225, 240));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(30, 40, 30, 40));

        GridBagConstraints gbc = new GridBagConstraints();

        JLabel titleLabel = new JLabel("👋 欢迎进入聊天室");
        titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 22));
        titleLabel.setForeground(TEXT_DARK);
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(0, 0, 6, 0);
        gbc.anchor = GridBagConstraints.CENTER;
        card.add(titleLabel, gbc);

        JLabel subtitleLabel = new JLabel("输入账号登录，或注册新账号开始畅聊");
        subtitleLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        subtitleLabel.setForeground(TEXT_GRAY);
        subtitleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 25, 0);
        card.add(subtitleLabel, gbc);

        JSeparator sep = new JSeparator() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(225, 228, 240));
                g2.drawLine(0, 0, getWidth(), 0);
                g2.dispose();
            }
        };
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 20, 0);
        card.add(sep, gbc);

        // 用户名标签
        JLabel nameLabel = new JLabel("用户名");
        nameLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        nameLabel.setForeground(TEXT_DARK);
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 8, 0);
        card.add(nameLabel, gbc);

        // 用户名输入框
        usernameField = createStyledTextField("请输入用户名");
        gbc.gridy = 4;
        gbc.insets = new Insets(0, 0, 16, 0);
        card.add(usernameField, gbc);

        // 密码标签
        JLabel passwordLabel = new JLabel("密码");
        passwordLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        passwordLabel.setForeground(TEXT_DARK);
        gbc.gridy = 5;
        gbc.insets = new Insets(0, 0, 8, 0);
        card.add(passwordLabel, gbc);

        // 密码输入框
        passwordField = new JPasswordField(20);
        passwordField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        passwordField.setForeground(TEXT_DARK);
        passwordField.setCaretColor(PRIMARY);
        passwordField.setBackground(BG_LIGHT);
        passwordField.setEchoChar('●');
        passwordField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 215, 230), 1, true),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)
        ));
        gbc.gridy = 6;
        gbc.insets = new Insets(0, 0, 16, 0);
        card.add(passwordField, gbc);

        // IP地址标签
        JLabel ipLabel = new JLabel("服务器IP地址");
        ipLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        ipLabel.setForeground(TEXT_DARK);
        gbc.gridy = 7;
        gbc.insets = new Insets(0, 0, 8, 0);
        card.add(ipLabel, gbc);

        // IP地址输入框
        ipField = createStyledTextField("例如：192.168.1.100");
        gbc.gridy = 8;
        gbc.insets = new Insets(0, 0, 16, 0);
        card.add(ipField, gbc);

        // 端口标签
        JLabel portLabel = new JLabel("端口号");
        portLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        portLabel.setForeground(TEXT_DARK);
        gbc.gridy = 9;
        gbc.insets = new Insets(0, 0, 8, 0);
        card.add(portLabel, gbc);

        // 端口输入框
        portField = createStyledTextField("例如：8080");
        gbc.gridy = 10;
        gbc.insets = new Insets(0, 0, 25, 0);
        card.add(portField, gbc);

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttonPanel.setOpaque(false);

        loginButton = createStyledButton("登录", PRIMARY, Color.WHITE, PRIMARY_HOVER);
        registerButton = createStyledButton("注册", CANCEL_BG, TEXT_DARK, CANCEL_HOVER);
        cancelButton = createStyledButton("取消", CANCEL_BG, TEXT_DARK, CANCEL_HOVER);

        buttonPanel.add(loginButton);
        buttonPanel.add(registerButton);
        buttonPanel.add(cancelButton);

        gbc.gridy = 11;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 5, 0);
        card.add(buttonPanel, gbc);

        wrapper.add(card);
        add(wrapper);

        loginButton.addActionListener(e -> onLogin());
        registerButton.addActionListener(e -> onRegister());
        cancelButton.addActionListener(e -> onCancel());
        usernameField.addActionListener(e -> passwordField.requestFocusInWindow());
        passwordField.addActionListener(e -> onLogin());
        ipField.addActionListener(e -> portField.requestFocusInWindow());
        portField.addActionListener(e -> onLogin());
        getRootPane().setDefaultButton(loginButton);

        pack();
        setLocationRelativeTo(null);
    }

    private JTextField createStyledTextField(String placeholder) {
        JTextField textField = new JTextField(20);
        textField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        textField.setForeground(TEXT_DARK);
        textField.setCaretColor(PRIMARY);
        textField.setBackground(BG_LIGHT);
        textField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 215, 230), 1, true),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)
        ));
        textField.setCaretPosition(0);
        textField.setToolTipText(placeholder);

        // 添加占位符效果
        textField.setText(placeholder);
        textField.setForeground(TEXT_GRAY);

        textField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                if (textField.getText().equals(placeholder)) {
                    textField.setText("");
                    textField.setForeground(TEXT_DARK);
                }
                textField.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(PRIMARY, 2, true),
                        BorderFactory.createEmptyBorder(9, 13, 9, 13)
                ));
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                if (textField.getText().isEmpty()) {
                    textField.setText(placeholder);
                    textField.setForeground(TEXT_GRAY);
                }
                textField.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(210, 215, 230), 1, true),
                        BorderFactory.createEmptyBorder(10, 14, 10, 14)
                ));
            }
        });

        return textField;
    }

    private JButton createStyledButton(String text, Color bg, Color fg, Color hoverBg) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (getModel().isPressed()) {
                    g2.setColor(hoverBg);
                } else if (getModel().isRollover()) {
                    g2.setColor(hoverBg);
                } else {
                    g2.setColor(bg);
                }
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 12, 12));
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        button.setForeground(fg);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(100, 42));
        button.setOpaque(false);

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) { button.repaint(); }
            @Override
            public void mouseExited(MouseEvent e) { button.repaint(); }
        });

        return button;
    }

    public String getUsername() {
        String username = usernameField.getText().trim();
        // 如果还是占位符文本，返回空字符串
        if (username.equals("请输入用户名")) {
            return "";
        }
        return username;
    }

    public String getPassword() {
        return new String(passwordField.getPassword()).trim();
    }

    public String getServerIP() {
        String ip = ipField.getText().trim();
        if (ip.equals("例如：192.168.1.100")) {
            return "";
        }
        return ip;
    }

    public String getPort() {
        String port = portField.getText().trim();
        if (port.equals("例如：8080")) {
            return "";
        }
        return port;
    }

    private void onLogin() {
        String username = getUsername();
        String password = getPassword();
        String ip = getServerIP();
        String port = getPort();

        if (!validateInputs(username, password, ip, port)) {
            return;
        }
        int portNum = Integer.parseInt(port);
        connectAndSubmit("登录", username, password, ip, portNum, true);
    }

    private void onRegister() {
        String username = getUsername();
        String password = getPassword();
        String ip = getServerIP();
        String port = getPort();

        if (!validateInputs(username, password, ip, port)) {
            return;
        }
        int portNum = Integer.parseInt(port);
        connectAndSubmit("注册", username, password, ip, portNum, false);
    }

    /** 表单校验，不通过时弹窗提示并返回 false */
    private boolean validateInputs(String username, String password, String ip, String port) {
        if (username.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入用户名", "提示", JOptionPane.WARNING_MESSAGE);
            usernameField.requestFocusInWindow();
            return false;
        }
        if (username.contains(":") || username.contains(",")) {
            JOptionPane.showMessageDialog(this, "用户名不能包含冒号（:）或逗号（,）", "提示",
                    JOptionPane.WARNING_MESSAGE);
            usernameField.requestFocusInWindow();
            return false;
        }
        if (password.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入密码", "提示", JOptionPane.WARNING_MESSAGE);
            passwordField.requestFocusInWindow();
            return false;
        }
        if (ip.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入服务器IP地址", "提示", JOptionPane.WARNING_MESSAGE);
            ipField.requestFocusInWindow();
            return false;
        }
        if (!isValidIP(ip)) {
            JOptionPane.showMessageDialog(this, "请输入有效的IP地址格式", "提示", JOptionPane.WARNING_MESSAGE);
            ipField.requestFocusInWindow();
            return false;
        }
        if (port.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入端口号", "提示", JOptionPane.WARNING_MESSAGE);
            portField.requestFocusInWindow();
            return false;
        }
        try {
            int portNum = Integer.parseInt(port);
            if (portNum < 1 || portNum > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "请输入有效的端口号（1-65535）", "提示",
                    JOptionPane.WARNING_MESSAGE);
            portField.requestFocusInWindow();
            return false;
        }
        return true;
    }

    /**
     * 在后台线程里连接服务器并提交登录/注册请求，等服务端结果：
     *  - 登录成功：打开聊天主窗口
     *  - 注册成功：提示后留在登录页
     *  - 失败：弹窗提示并恢复表单
     *
     * 为什么不能直接在点击按钮的方法里连服务器：connect() 要等 TCP 握手，
     * 放在界面线程里做的话窗口会卡死（转圈、拖不动），所以放进 new Thread 里。
     */
    private void connectAndSubmit(String action, String username, String password,
                                  String ip, int portNum, boolean isLogin) {
        loginButton.setEnabled(false);
        registerButton.setEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        new Thread(() -> {
            chatClient client = new chatClient();
            // CountDownLatch(1)：等一个信号。登录结果是异步回调返回的，
            // 回调一到达就 countDown()，下面的 await() 才会结束等待
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] success = {false};
            final String[] reason = {""};

            // 临时监听器：只收集登录/注册结果，结果到达后放行下面的等待
            client.setListener(new chatClient.Listener() {
                @Override
                public void onLoginResult(boolean ok, String r) { success[0] = ok; reason[0] = r; latch.countDown(); }
                @Override
                public void onRegisterResult(boolean ok, String r) { success[0] = ok; reason[0] = r; latch.countDown(); }
                @Override
                public void onDisconnected(String r) { reason[0] = r; latch.countDown(); }
                @Override
                public void onSystemMessage(String c) {}
                @Override
                public void onChatMessage(String s, String m, long t, String c) {}
                @Override
                public void onUserList(String[] u) {}
                @Override
                public void onOfflineUsers(String[] u) {}
            });

            try {
                client.connect(ip, portNum);
                if (isLogin) {
                    client.login(username, password);
                } else {
                    client.register(username, password);
                }

                // 等服务端返回结果（最多等 5 秒，超时按失败处理，
                // 不然服务器没开的话这里会一直卡着）
                if (!latch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IOException("服务器响应超时，请检查服务器是否正常运行");
                }
                if (!success[0]) {
                    throw new IOException(reason[0]);
                }

                // 注册成功后连接不再需要，关闭
                if (!isLogin) {
                    client.logout();
                }
                final chatClient connected = client;
                // 现在在后台线程里，不能直接在这里 new 窗口/弹对话框，
                // 必须切回界面线程（EDT）再做 UI 操作
                SwingUtilities.invokeLater(() -> {
                    if (isLogin) {
                        // 登录成功，打开聊天主窗口（窗口构造时会注册正式的消息监听器）
                        new clientChatUI(username, ip, portNum, connected).setVisible(true);
                        dispose();
                    } else {
                        JOptionPane.showMessageDialog(this, "注册成功，请登录", "提示",
                                JOptionPane.INFORMATION_MESSAGE);
                        resetButtons();
                    }
                });
            } catch (Exception e) {
                client.logout();
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, action + "失败：" + e.getMessage(),
                            action + "失败", JOptionPane.ERROR_MESSAGE);
                    resetButtons();
                });
            }
        }, "connect-server").start();
    }

    private void resetButtons() {
        loginButton.setEnabled(true);
        registerButton.setEnabled(true);
        setCursor(Cursor.getDefaultCursor());
    }

    private boolean isValidIP(String ip) {
        // 简单的IP地址格式验证
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        for (String part : parts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private void onCancel() {
        dispose();
        System.exit(0);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new chatEntryUI().setVisible(true);
        });
    }
}
