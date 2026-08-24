package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class clientChatUI extends JFrame {

    // UI组件
    private JTextPane chatArea;
    private JTextField inputField;
    private JButton sendButton;
    private JButton emojiButton;
    private JButton fileButton;
    private JPanel onlineUsersPanel;
    private JLabel connectionStatusLabel;
    private JLabel userCountLabel;

    // 用户信息
    private String nickname;
    private String serverIP;
    private int serverPort;

    // 在线用户列表
    private DefaultListModel<String> onlineUsersModel;
    private JList<String> onlineUsersList;
    private Map<String, Color> userColors = new ConcurrentHashMap<>();

    // 颜色定义
    private static final Color PRIMARY = new Color(99, 132, 255);
    private static final Color PRIMARY_HOVER = new Color(75, 108, 235);
    private static final Color BG_LIGHT = new Color(245, 247, 252);
    private static final Color SIDEBAR_BG = new Color(248, 249, 252);
    private static final Color CARD_BG = Color.WHITE;
    private static final Color TEXT_DARK = new Color(44, 52, 74);
    private static final Color TEXT_GRAY = new Color(140, 149, 168);
    private static final Color ONLINE_GREEN = new Color(52, 199, 123);
    private static final Color MESSAGE_BG = new Color(240, 243, 250);
    private static final Color MY_MESSAGE_BG = new Color(99, 132, 255);
    private static final Color SYSTEM_MSG_COLOR = new Color(150, 155, 170);

    // 简单的表情符号映射
    private static final String[] EMOJIS = {"😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎",
            "🤔", "🤗", "😅", "😉", "🙃", "😋", "😴", "🤯",
            "😇", "🥳", "😭", "😤", "👍", "👎", "👏", "🙏",
            "💪", "🤝", "❤️", "💔", "🎉", "✨", "🔥", "💯"};

    private JPopupMenu emojiPopup;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

    public clientChatUI(String nickname, String serverIP, int serverPort) {
        this.nickname = nickname;
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        initUI();
        appendSystemMessage("欢迎 " + nickname + " 加入聊天室！");
        appendSystemMessage("您已连接到服务器 " + serverIP + ":" + serverPort);
    }

    private void initUI() {
        setTitle("局域网聊天室 - " + nickname);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 650);
        setMinimumSize(new Dimension(800, 600));
        setLocationRelativeTo(null);

        // 主面板
        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(BG_LIGHT);

        // 顶部信息栏
        mainPanel.add(createTopBar(), BorderLayout.NORTH);

        // 中间聊天区域和在线用户列表
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createChatPanel(), createOnlineUsersPanel());
        splitPane.setDividerLocation(650);
        splitPane.setDividerSize(1);
        splitPane.setBorder(null);
        splitPane.setEnabled(false);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        // 底部输入区域
        mainPanel.add(createInputPanel(), BorderLayout.SOUTH);

        add(mainPanel);

        // 创建表情选择弹窗
        createEmojiPopup();

        // 添加窗口关闭监听
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                showExitConfirmation();
            }
        });
    }

    private JPanel createTopBar() {
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(CARD_BG);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 225, 235)),
                BorderFactory.createEmptyBorder(12, 20, 12, 20)
        ));

        // 左侧：标题和连接信息
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftPanel.setOpaque(false);

        JLabel titleLabel = new JLabel("💬 聊天室");
        titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        titleLabel.setForeground(TEXT_DARK);
        leftPanel.add(titleLabel);

        connectionStatusLabel = new JLabel("● 已连接");
        connectionStatusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        connectionStatusLabel.setForeground(ONLINE_GREEN);
        leftPanel.add(connectionStatusLabel);

        topBar.add(leftPanel, BorderLayout.WEST);

        // 右侧：用户信息
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightPanel.setOpaque(false);

        JLabel serverInfoLabel = new JLabel("服务器: " + serverIP + ":" + serverPort);
        serverInfoLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        serverInfoLabel.setForeground(TEXT_GRAY);
        rightPanel.add(serverInfoLabel);

        JLabel avatarLabel = createAvatarLabel(nickname);
        rightPanel.add(avatarLabel);

        JLabel nicknameLabel = new JLabel(nickname);
        nicknameLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        nicknameLabel.setForeground(TEXT_DARK);
        rightPanel.add(nicknameLabel);

        topBar.add(rightPanel, BorderLayout.EAST);

        return topBar;
    }

    private JPanel createChatPanel() {
        JPanel chatPanel = new JPanel(new BorderLayout(0, 0));
        chatPanel.setBackground(CARD_BG);

        // 聊天消息显示区域
        chatArea = new JTextPane();
        chatArea.setEditable(false);
        chatArea.setBackground(CARD_BG);
        // 设置支持Emoji的字体
        Font chatFont = getEmojiFont(14);
        chatArea.setFont(chatFont);
        chatArea.setBorder(new EmptyBorder(10, 15, 10, 15));

        JScrollPane chatScrollPane = new JScrollPane(chatArea);
        chatScrollPane.setBorder(null);
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        chatScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chatPanel.add(chatScrollPane, BorderLayout.CENTER);

        return chatPanel;
    }

    private JPanel createOnlineUsersPanel() {
        JPanel usersPanel = new JPanel(new BorderLayout(0, 0));
        usersPanel.setBackground(SIDEBAR_BG);
        usersPanel.setPreferredSize(new Dimension(200, 0));
        usersPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(220, 225, 235)));

        // 标题
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(SIDEBAR_BG);
        headerPanel.setBorder(new EmptyBorder(15, 15, 10, 15));

        JLabel headerLabel = new JLabel("在线用户");
        headerLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        headerLabel.setForeground(TEXT_DARK);
        headerPanel.add(headerLabel, BorderLayout.WEST);

        userCountLabel = new JLabel("1人在线"); // 初始只有自己在线
        userCountLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        userCountLabel.setForeground(TEXT_GRAY);
        headerPanel.add(userCountLabel, BorderLayout.EAST);

        usersPanel.add(headerPanel, BorderLayout.NORTH);

        // 在线用户列表
        onlineUsersModel = new DefaultListModel<>();
        onlineUsersList = new JList<>(onlineUsersModel);
        onlineUsersList.setBackground(SIDEBAR_BG);
        onlineUsersList.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        onlineUsersList.setForeground(TEXT_DARK);
        onlineUsersList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        onlineUsersList.setCellRenderer(new UserListCellRenderer());
        onlineUsersList.setFixedCellHeight(40);

        JScrollPane usersScrollPane = new JScrollPane(onlineUsersList);
        usersScrollPane.setBorder(null);
        usersScrollPane.setBackground(SIDEBAR_BG);
        usersPanel.add(usersScrollPane, BorderLayout.CENTER);

        // 添加当前用户到在线列表
        onlineUsersModel.addElement(nickname);

        return usersPanel;
    }

    private JPanel createInputPanel() {
        JPanel inputPanel = new JPanel(new BorderLayout(8, 0));
        inputPanel.setBackground(CARD_BG);
        inputPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 225, 235)),
                BorderFactory.createEmptyBorder(15, 20, 15, 20)
        ));

        // 左侧按钮组
        JPanel buttonGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        buttonGroup.setOpaque(false);

        emojiButton = createIconButton("😊", "表情");
        emojiButton.addActionListener(e -> showEmojiPopup());
        buttonGroup.add(emojiButton);

        fileButton = createIconButton("📎", "发送文件");
        fileButton.addActionListener(e -> sendFile());
        buttonGroup.add(fileButton);

        inputPanel.add(buttonGroup, BorderLayout.WEST);

        // 中间输入框
        inputField = new JTextField();
        // 设置支持Emoji的字体
        inputField.setFont(getEmojiFont(14));
        inputField.setForeground(TEXT_DARK);
        inputField.setBackground(BG_LIGHT);
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 215, 230), 1, true),
                BorderFactory.createEmptyBorder(10, 15, 10, 15)
        ));
        inputField.addActionListener(e -> sendMessage());
        inputPanel.add(inputField, BorderLayout.CENTER);

        // 右侧发送按钮
        sendButton = createStyledButton("发送", PRIMARY, Color.WHITE, PRIMARY_HOVER);
        sendButton.setPreferredSize(new Dimension(80, 40));
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(sendButton, BorderLayout.EAST);

        return inputPanel;
    }

    private void createEmojiPopup() {
        emojiPopup = new JPopupMenu();
        JPanel emojiPanel = new JPanel(new GridLayout(4, 8, 5, 5));
        emojiPanel.setBackground(CARD_BG);
        emojiPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        for (String emoji : EMOJIS) {
            JButton emojiBtn = new JButton(emoji);
            // 设置支持Emoji的字体
            emojiBtn.setFont(getEmojiFont(20));
            emojiBtn.setPreferredSize(new Dimension(40, 40));
            emojiBtn.setBackground(CARD_BG);
            emojiBtn.setBorder(BorderFactory.createLineBorder(new Color(230, 233, 240), 1));
            emojiBtn.setFocusPainted(false);
            emojiBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));

            final String emojiText = emoji;
            emojiBtn.addActionListener(e -> {
                inputField.setText(inputField.getText() + emojiText);
                emojiPopup.setVisible(false);
                inputField.requestFocusInWindow();
            });

            // 悬停效果
            emojiBtn.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    emojiBtn.setBackground(BG_LIGHT);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    emojiBtn.setBackground(CARD_BG);
                }
            });

            emojiPanel.add(emojiBtn);
        }

        emojiPopup.add(emojiPanel);
    }

    private void showEmojiPopup() {
        emojiPopup.show(emojiButton, 0, emojiButton.getHeight());
    }

    private void sendFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("选择要发送的文件");
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            String fileName = fileChooser.getSelectedFile().getName();
            appendMessage("📎 文件发送: " + fileName, nickname, true);
            // 这里应该实现实际的文件发送逻辑
        }
    }

    private void sendMessage() {
        String message = inputField.getText().trim();
        if (!message.isEmpty()) {
            appendMessage(message, nickname, true);
            inputField.setText("");
            inputField.requestFocusInWindow();
            // 这里应该实现实际的消息发送逻辑
        }
    }

    private void appendMessage(String message, String sender, boolean isMine) {
        StyledDocument doc = chatArea.getStyledDocument();

        // 添加时间戳
        Style timeStyle = chatArea.addStyle("TimeStyle", null);
        StyleConstants.setForeground(timeStyle, SYSTEM_MSG_COLOR);
        StyleConstants.setFontSize(timeStyle, 11);
        StyleConstants.setFontFamily(timeStyle, "Dialog"); // 时间戳使用普通字体

        String timeStr = "[" + timeFormat.format(new Date()) + "] ";

        // 添加发送者
        Style senderStyle = chatArea.addStyle("SenderStyle", null);
        StyleConstants.setForeground(senderStyle, getColorForUser(sender));
        StyleConstants.setBold(senderStyle, true);
        StyleConstants.setFontSize(senderStyle, 13);
        StyleConstants.setFontFamily(senderStyle, "Microsoft YaHei"); // 发送者使用中文字体

        // 添加消息内容 - 使用支持Emoji的字体
        Style msgStyle = chatArea.addStyle("MsgStyle", null);
        StyleConstants.setForeground(msgStyle, isMine ? Color.WHITE : TEXT_DARK);
        StyleConstants.setFontSize(msgStyle, 14);
        StyleConstants.setBold(msgStyle, false);

        // 设置支持Emoji的字体
        String emojiFontName = isFontAvailable("Segoe UI Emoji") ? "Segoe UI Emoji" : "Dialog";
        StyleConstants.setFontFamily(msgStyle, emojiFontName);

        try {
            if (isMine) {
                doc.insertString(doc.getLength(), "  ", msgStyle);
            }
            doc.insertString(doc.getLength(), timeStr, timeStyle);
            doc.insertString(doc.getLength(), sender + ": ", senderStyle);
            doc.insertString(doc.getLength(), message + "\n", msgStyle);

            // 自动滚动到底部
            chatArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private Color getColorForUser(String username) {
        if (!userColors.containsKey(username)) {
            // 生成稳定的用户颜色
            int hash = username.hashCode();
            float hue = Math.abs(hash % 360) / 360.0f;
            Color color = Color.getHSBColor(hue, 0.6f, 0.8f);
            userColors.put(username, color);
        }
        return userColors.get(username);
    }

    private JLabel createAvatarLabel(String username) {
        JLabel avatarLabel = new JLabel();
        avatarLabel.setPreferredSize(new Dimension(30, 30));
        avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
        avatarLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        avatarLabel.setForeground(Color.WHITE);
        avatarLabel.setOpaque(true);
        avatarLabel.setBackground(getColorForUser(username));
        avatarLabel.setText(username.substring(0, 1).toUpperCase());
        avatarLabel.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
        avatarLabel.setCursor(new Cursor(Cursor.HAND_CURSOR));
        avatarLabel.setToolTipText(username);
        return avatarLabel;
    }

    private JButton createIconButton(String text, String tooltip) {
        JButton button = new JButton(text);
        // 设置支持Emoji的字体
        button.setFont(getEmojiFont(20));
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(40, 40));
        button.setBackground(CARD_BG);
        button.setBorder(BorderFactory.createLineBorder(new Color(220, 225, 235), 1, true));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(BG_LIGHT);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(CARD_BG);
            }
        });

        return button;
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
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 10, 10));
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

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) { button.repaint(); }
            @Override
            public void mouseExited(MouseEvent e) { button.repaint(); }
        });

        return button;
    }

    // 用户列表自定义渲染器
    private class UserListCellRenderer extends JPanel implements ListCellRenderer<String> {
        private JLabel avatarLabel;
        private JLabel nameLabel;
        private JLabel statusLabel;

        public UserListCellRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(5, 10, 5, 10));
            setOpaque(true);

            avatarLabel = new JLabel();
            avatarLabel.setPreferredSize(new Dimension(28, 28));
            avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
            avatarLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
            avatarLabel.setForeground(Color.WHITE);
            avatarLabel.setOpaque(true);

            JPanel centerPanel = new JPanel(new BorderLayout(0, 2));
            centerPanel.setOpaque(false);

            nameLabel = new JLabel();
            nameLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            nameLabel.setForeground(TEXT_DARK);

            statusLabel = new JLabel("●");
            statusLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
            statusLabel.setForeground(ONLINE_GREEN);
            statusLabel.setHorizontalAlignment(SwingConstants.RIGHT);

            centerPanel.add(nameLabel, BorderLayout.CENTER);
            centerPanel.add(statusLabel, BorderLayout.EAST);

            add(avatarLabel, BorderLayout.WEST);
            add(centerPanel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends String> list, String value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            if (value != null) {
                avatarLabel.setText(value.substring(0, 1).toUpperCase());
                avatarLabel.setBackground(getColorForUser(value));
                nameLabel.setText(value);
            }

            if (isSelected) {
                setBackground(new Color(235, 238, 250));
            } else {
                setBackground(SIDEBAR_BG);
            }

            return this;
        }
    }

    private void appendSystemMessage(String message) {
        StyledDocument doc = chatArea.getStyledDocument();
        Style systemStyle = chatArea.addStyle("SystemStyle", null);
        StyleConstants.setForeground(systemStyle, SYSTEM_MSG_COLOR);
        StyleConstants.setFontSize(systemStyle, 12);
        StyleConstants.setItalic(systemStyle, true);
        StyleConstants.setFontFamily(systemStyle, "Microsoft YaHei");

        try {
            doc.insertString(doc.getLength(), "ℹ️ " + message + "\n", systemStyle);
            chatArea.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void updateUserCount() {
        userCountLabel.setText(onlineUsersModel.size() + "人在线");
    }

    private void showExitConfirmation() {
        int result = JOptionPane.showConfirmDialog(this,
                "确定要退出聊天室吗？",
                "退出确认",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            dispose();
            System.exit(0);
        } else {
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        }
    }

    // 辅助方法：获取支持Emoji的字体
    private Font getEmojiFont(int size) {
        if (isFontAvailable("Segoe UI Emoji")) {
            return new Font("Segoe UI Emoji", Font.PLAIN, size);
        } else if (isFontAvailable("Noto Color Emoji")) {
            return new Font("Noto Color Emoji", Font.PLAIN, size);
        } else if (isFontAvailable("Apple Color Emoji")) {
            return new Font("Apple Color Emoji", Font.PLAIN, size);
        } else {
            // 使用默认字体，通常也能显示Emoji
            return new Font("Dialog", Font.PLAIN, size);
        }
    }

    // 辅助方法：检查字体是否可用
    private boolean isFontAvailable(String fontName) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String[] fontNames = ge.getAvailableFontFamilyNames();
        for (String name : fontNames) {
            if (name.equalsIgnoreCase(fontName)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            // 创建聊天界面
            clientChatUI chatUI = new clientChatUI("测试用户", "192.168.1.100", 8080);
            chatUI.setVisible(true);
        });
    }
}