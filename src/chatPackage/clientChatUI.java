package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.io.IOException;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class clientChatUI extends JFrame implements chatClient.Listener {

    // UI组件
    private JTextPane chatArea;
    private JTextField inputField;
    private JButton sendButton;
    private JButton emojiButton;
    private JPanel onlineUsersPanel;
    private JLabel connectionStatusLabel;
    private JLabel userCountLabel;

    // 用户信息
    private String nickname;
    private String serverIP;
    private int serverPort;

    // 网络层
    private chatClient client;
    private boolean disconnectedNotified;

    // 当前用户角色（登录成功后由服务端下发）：admin 管理员 / user 普通用户
    private volatile String role = "user";

    // 样式名序号：保证每次插入消息都使用全新的样式对象
    private int styleSeq;

    // 用户列表（在线 + 离线合并显示）
    private DefaultListModel<UserEntry> userListModel;
    private JList<UserEntry> userList;
    private Map<String, Color> userColors = new ConcurrentHashMap<>();

    // 在线/离线用户集合（由服务端广播更新，合并显示时在线用户排前面）
    private final Set<String> onlineUsers = new LinkedHashSet<>();
    private final Set<String> offlineUsers = new LinkedHashSet<>();

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

    public clientChatUI(String nickname, String serverIP, int serverPort, chatClient client) {
        this.nickname = nickname;
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        this.client = client;
        // 注册消息回调（回调发生在接收线程，实现里统一用 invokeLater 切回 EDT 更新界面）
        // 注意：登录验证已在登录界面完成，这里只接管消息渲染，不能再调用 login()
        client.setListener(this);
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
        // 使用中文字体（微软雅黑含中文和大部分 emoji 字形，emoji 字体不含中文字形会导致中文显示为方块）
        chatArea.setFont(getChatFont(14));
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

        // 用户列表（登录后由服务端广播的在线/离线用户数据填充）
        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setBackground(SIDEBAR_BG);
        userList.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        userList.setForeground(TEXT_DARK);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new UserListCellRenderer());
        userList.setFixedCellHeight(40);

        JScrollPane usersScrollPane = new JScrollPane(userList);
        usersScrollPane.setBorder(null);
        usersScrollPane.setBackground(SIDEBAR_BG);
        usersPanel.add(usersScrollPane, BorderLayout.CENTER);

        // 右键弹出用户管理菜单（踢人/封禁）
        userList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { showUserPopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { showUserPopup(e); }
        });

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

        inputPanel.add(buttonGroup, BorderLayout.WEST);

        // 中间输入框
        inputField = new JTextField();
        // 使用中文字体，保证中文输入正常显示
        inputField.setFont(getChatFont(14));
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

    private void sendMessage() {
        String message = inputField.getText().trim();
        if (!message.isEmpty()) {
            // 消息发给服务器，由服务器广播回来后在回调中统一渲染（保证所有人看到的时间一致）
            client.sendMessage(message);
            inputField.setText("");
            inputField.requestFocusInWindow();
        }
    }

    private void appendMessage(String message, String sender, boolean isMine) {
        StyledDocument doc = chatArea.getStyledDocument();

        // 每次使用唯一的样式名，避免 addStyle 同名复用导致样式被后续消息覆盖
        // 添加时间戳
        Style timeStyle = chatArea.addStyle("TimeStyle" + (++styleSeq), null);
        StyleConstants.setForeground(timeStyle, SYSTEM_MSG_COLOR);
        StyleConstants.setFontSize(timeStyle, 11);
        StyleConstants.setFontFamily(timeStyle, "Dialog"); // 时间戳使用普通字体

        String timeStr = "[" + timeFormat.format(new Date()) + "] ";

        // 添加发送者
        Style senderStyle = chatArea.addStyle("SenderStyle" + (++styleSeq), null);
        StyleConstants.setForeground(senderStyle, getColorForUser(sender));
        StyleConstants.setBold(senderStyle, true);
        StyleConstants.setFontSize(senderStyle, 13);
        StyleConstants.setFontFamily(senderStyle, "Microsoft YaHei"); // 发送者使用中文字体

        // 添加消息内容 - 使用支持Emoji的字体
        // 自己的消息用主题蓝（聊天区背景是白色，白字会看不见），别人的用深色
        Style msgStyle = chatArea.addStyle("MsgStyle" + (++styleSeq), null);
        StyleConstants.setForeground(msgStyle, isMine ? PRIMARY : TEXT_DARK);
        StyleConstants.setFontSize(msgStyle, 14);
        StyleConstants.setBold(msgStyle, false);

        // 消息内容使用中文字体（emoji 字体没有中文字形，中文会显示为方块）
        StyleConstants.setFontFamily(msgStyle, isFontAvailable("Microsoft YaHei") ? "Microsoft YaHei" : "Dialog");

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

    // 用户专属颜色调色板：色相均匀分布的 16 种高区分度颜色。
    // 旧实现用 hashCode % 360 当色相，相近昵称的 hash 只差 1，颜色几乎一样，
    // 视觉上就像所有用户都被硬编码成了同一种颜色。
    private static final int USER_COLOR_COUNT = 16;

    private Color getColorForUser(String username) {
        if (!userColors.containsKey(username)) {
            // 按用户名哈希稳定分配调色板颜色，同一用户颜色永远不变
            int index = Math.floorMod(username.hashCode(), USER_COLOR_COUNT);
            float hue = index * (360.0f / USER_COLOR_COUNT);
            Color color = Color.getHSBColor(hue / 360.0f, 0.7f, 0.85f);
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

    /** 用户列表条目：用户名 + 在线状态 */
    private static class UserEntry {
        final String name;
        final boolean online;

        UserEntry(String name, boolean online) {
            this.name = name;
            this.online = online;
        }
    }

    // 用户列表自定义渲染器（在线绿色状态点，离线灰色状态点和灰显名字）
    private class UserListCellRenderer extends JPanel implements ListCellRenderer<UserEntry> {
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
        public Component getListCellRendererComponent(JList<? extends UserEntry> list, UserEntry value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            if (value != null) {
                avatarLabel.setText(value.name.substring(0, 1).toUpperCase());
                avatarLabel.setBackground(getColorForUser(value.name));
                nameLabel.setText(value.name);
                // 在线用户绿色状态点 + 深色名字；离线用户灰色状态点 + 灰显名字
                statusLabel.setForeground(value.online ? ONLINE_GREEN : new Color(170, 175, 190));
                nameLabel.setForeground(value.online ? TEXT_DARK : new Color(160, 165, 180));
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

    /** 用户列表右键菜单：管理员可踢出/封禁用户，普通用户提示无权限 */
    private void showUserPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int index = userList.locationToIndex(e.getPoint());
        if (index < 0) {
            return;
        }
        UserEntry entry = userListModel.get(index);
        if (entry.name.equals(nickname)) {
            return; // 不能操作自己
        }

        JPopupMenu menu = new JPopupMenu();
        if ("admin".equals(role)) {
            JMenuItem kickItem = new JMenuItem("踢出用户");
            kickItem.addActionListener(ev -> client.kick(entry.name));

            JMenuItem banItem = new JMenuItem("封禁用户（删除账号）");
            banItem.addActionListener(ev -> {
                int ok = JOptionPane.showConfirmDialog(this,
                        "确定要封禁并删除用户「" + entry.name + "」吗？\n此操作将从数据库删除该账号，不可恢复！",
                        "封禁确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ok == JOptionPane.YES_OPTION) {
                    client.ban(entry.name);
                }
            });
            menu.add(kickItem);
            menu.add(banItem);
        } else {
            JMenuItem noPermItem = new JMenuItem("仅管理员可操作");
            noPermItem.setEnabled(false);
            menu.add(noPermItem);
        }
        menu.show(userList, e.getX(), e.getY());
    }

    /** 按在线优先的顺序重建合并用户列表 */
    private void refreshUserList() {
        userListModel.clear();
        for (String user : onlineUsers) {
            userListModel.addElement(new UserEntry(user, true));
        }
        for (String user : offlineUsers) {
            userListModel.addElement(new UserEntry(user, false));
        }
        updateUserCount();
    }

    private void updateUserCount() {
        userCountLabel.setText(onlineUsers.size() + "人在线 · 共" + (onlineUsers.size() + offlineUsers.size()) + "人");
    }

    private void showExitConfirmation() {
        int result = JOptionPane.showConfirmDialog(this,
                "确定要退出聊天室吗？",
                "退出确认",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);

        if (result == JOptionPane.YES_OPTION) {
            // 通知服务器自己下线并关闭连接
            client.logout();
            dispose();
            System.exit(0);
        } else {
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        }
    }

    // ===== chatClient.Listener 回调（发生在接收线程，统一切回 EDT 更新界面） =====

    @Override
    public void onLoginResult(boolean success, String reason) {
        // 登录已在登录界面完成，聊天窗口内不处理
    }

    @Override
    public void onRegisterResult(boolean success, String reason) {
        // 注册在登录界面完成，聊天窗口内不处理
    }

    @Override
    public void onSystemMessage(String content) {
        SwingUtilities.invokeLater(() -> appendSystemMessage(content));
    }

    @Override
    public void onRole(String role) {
        this.role = role;
    }

    @Override
    public void onKicked(String reason) {
        SwingUtilities.invokeLater(() -> {
            disconnectedNotified = true; // 防止随后的断线回调重复弹窗
            appendSystemMessage("⚠️ " + reason);
            JOptionPane.showMessageDialog(this, reason, "已被踢出", JOptionPane.WARNING_MESSAGE);
            dispose();
        });
    }

    @Override
    public void onBanned(String reason) {
        SwingUtilities.invokeLater(() -> {
            disconnectedNotified = true;
            appendSystemMessage("⚠️ " + reason);
            JOptionPane.showMessageDialog(this, reason, "已被封禁", JOptionPane.WARNING_MESSAGE);
            dispose();
        });
    }

    @Override
    public void onChatMessage(String sender, String content) {
        SwingUtilities.invokeLater(() -> appendMessage(content, sender, sender.equals(nickname)));
    }

    @Override
    public void onUserList(String[] users) {
        SwingUtilities.invokeLater(() -> {
            onlineUsers.clear();
            for (String user : users) {
                onlineUsers.add(user);
            }
            refreshUserList();
        });
    }

    @Override
    public void onOfflineUsers(String[] users) {
        SwingUtilities.invokeLater(() -> {
            offlineUsers.clear();
            for (String user : users) {
                offlineUsers.add(user);
            }
            refreshUserList();
        });
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (disconnectedNotified) {
                return;
            }
            disconnectedNotified = true;
            connectionStatusLabel.setText("● 已断开");
            connectionStatusLabel.setForeground(new Color(220, 60, 60));
            appendSystemMessage("与服务器的连接已断开：" + reason);
            JOptionPane.showMessageDialog(this, "与服务器的连接已断开：\n" + reason,
                    "连接断开", JOptionPane.WARNING_MESSAGE);
            dispose();
        });
    }

    // 辅助方法：获取中文字体（微软雅黑支持中文和大部分 emoji）
    private Font getChatFont(int size) {
        if (isFontAvailable("Microsoft YaHei")) {
            return new Font("Microsoft YaHei", Font.PLAIN, size);
        }
        return new Font("Dialog", Font.PLAIN, size);
    }

    // 辅助方法：获取支持Emoji的字体（仅用于只显示 emoji 的按钮）
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

            // 测试入口：直连本机服务器并登录（正式入口是 chatEntryUI）
            try {
                chatClient client = new chatClient();
                client.connect("127.0.0.1", 8080);
                client.setListener(new chatClient.Listener() {
                    @Override
                    public void onLoginResult(boolean ok, String r) {
                        if (ok) {
                            new clientChatUI("测试用户", "127.0.0.1", 8080, client).setVisible(true);
                        } else {
                            JOptionPane.showMessageDialog(null, "登录失败：" + r,
                                    "错误", JOptionPane.ERROR_MESSAGE);
                            System.exit(1);
                        }
                    }
                    @Override
                    public void onRegisterResult(boolean ok, String r) {}
                    @Override
                    public void onSystemMessage(String c) {}
                    @Override
                    public void onChatMessage(String s, String c) {}
                    @Override
                    public void onUserList(String[] u) {}
                    @Override
                    public void onOfflineUsers(String[] u) {}
                    @Override
                    public void onDisconnected(String r) {
                        JOptionPane.showMessageDialog(null, "连接断开：" + r,
                                "错误", JOptionPane.ERROR_MESSAGE);
                        System.exit(1);
                    }
                });
                client.login("测试用户", "123456");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, "无法连接服务器：" + e.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}