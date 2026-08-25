package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主聊天窗口：公共聊天室 + 用户列表 + 私聊入口。
 *
 * 本类是 chatClient 唯一的监听器（chatClient 是单监听器设计，setListener 为整体替换），
 * 因此所有消息都先到这里，再由这里路由到对应的私聊窗口。privateChatUI 不允许自己
 * 注册监听器，否则主窗口会立刻失去全部回调。
 *
 * 线程约定：chatClient 的回调发生在网络接收线程上，所有回调实现都用
 * SwingUtilities.invokeLater 切回 EDT 之后再碰界面和下面这两张表。
 */
public class clientChatUI extends JFrame implements chatClient.Listener {

    /** 公共历史加载超时，服务端中途断开时不至于让界面一直空着 */
    private static final int HISTORY_TIMEOUT_MS = 10_000;

    // UI组件
    private JTextPane chatArea;
    private JTextField inputField;
    private JButton emojiButton;
    private JButton clearPublicButton;
    private JLabel connectionStatusLabel;
    private JLabel userCountLabel;

    // 用户信息
    private final String nickname;
    private final String serverIP;
    private final int serverPort;

    // 网络层
    private final chatClient client;
    private boolean disconnectedNotified;

    // 当前用户角色（登录成功后由服务端下发）：admin 管理员 / user 普通用户
    private volatile String role = "user";

    // 用户列表（在线 + 离线合并显示）
    private DefaultListModel<UserEntry> userListModel;
    private JList<UserEntry> userList;

    // 在线/离线用户集合（由服务端广播更新，合并显示时在线用户排前面）
    private final Set<String> onlineUsers = new LinkedHashSet<>();
    private final Set<String> offlineUsers = new LinkedHashSet<>();

    /** 已打开的私聊窗口：会话对方 -> 窗口。只在 EDT 上访问 */
    private final Map<String, privateChatUI> privateWindows = new ConcurrentHashMap<>();

    /** 各会话的未读条数，值一律取服务端下发的权威计数，不在本地自增 */
    private final Map<String, Integer> unreadCounts = new ConcurrentHashMap<>();

    /**
     * 公共历史是否加载完毕。加载期间到达的实时消息先缓冲，等 HISTEND 后再补渲染。
     * 原因与私聊窗口相同：服务端的历史回放和实时广播是两个线程写同一个 socket，
     * 跨行顺序没有保证，不缓冲就会出现重复或乱序。
     */
    private boolean historyLoaded;
    private final List<Object[]> pendingPublic = new ArrayList<>();
    private Timer historyTimeout;

    private JPopupMenu emojiPopup;

    public clientChatUI(String nickname, String serverIP, int serverPort, chatClient client) {
        this.nickname = nickname;
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        this.client = client;
        // 注册消息回调（回调发生在接收线程，实现里统一用 invokeLater 切回 EDT 更新界面）
        // 注意：登录验证已在登录界面完成，这里只接管消息渲染，不能再调用 login()
        client.setListener(this);
        initUI();
        beginHistoryLoad();
    }

    private void initUI() {
        setTitle("局域网聊天室 - " + nickname);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(900, 650);
        setMinimumSize(new Dimension(800, 600));
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(chatTheme.BG_LIGHT);
        mainPanel.add(createTopBar(), BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createChatPanel(), createOnlineUsersPanel());
        splitPane.setDividerLocation(650);
        splitPane.setDividerSize(1);
        splitPane.setBorder(null);
        splitPane.setEnabled(false);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        mainPanel.add(createInputPanel(), BorderLayout.SOUTH);
        add(mainPanel);

        emojiPopup = chatTheme.createEmojiPopup(inputField);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                showExitConfirmation();
            }
        });
    }

    private JPanel createTopBar() {
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(chatTheme.CARD_BG);
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, chatTheme.BORDER),
                new EmptyBorder(12, 20, 12, 20)
        ));

        // 左侧：标题和连接信息
        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftPanel.setOpaque(false);

        JLabel titleLabel = new JLabel("💬 聊天室");
        titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        titleLabel.setForeground(chatTheme.TEXT_DARK);
        leftPanel.add(titleLabel);

        connectionStatusLabel = new JLabel("● 已连接");
        connectionStatusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        connectionStatusLabel.setForeground(chatTheme.ONLINE_GREEN);
        leftPanel.add(connectionStatusLabel);

        topBar.add(leftPanel, BorderLayout.WEST);

        // 右侧：管理员操作 + 用户信息
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightPanel.setOpaque(false);

        // 清空公共记录：仅管理员可见，角色由服务端在 onRole 回调里下发后才显示
        clearPublicButton = chatTheme.createStyledButton("🗑 清空聊天记录",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        clearPublicButton.setPreferredSize(new Dimension(120, 32));
        clearPublicButton.setVisible(false);
        clearPublicButton.addActionListener(e -> confirmClearPublic());
        rightPanel.add(clearPublicButton);

        JLabel serverInfoLabel = new JLabel("服务器: " + serverIP + ":" + serverPort);
        serverInfoLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        serverInfoLabel.setForeground(chatTheme.TEXT_GRAY);
        rightPanel.add(serverInfoLabel);

        JLabel avatarLabel = chatTheme.createAvatarLabel(nickname, 30, 14);
        avatarLabel.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
        rightPanel.add(avatarLabel);

        JLabel nicknameLabel = new JLabel(nickname);
        nicknameLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        nicknameLabel.setForeground(chatTheme.TEXT_DARK);
        rightPanel.add(nicknameLabel);

        topBar.add(rightPanel, BorderLayout.EAST);
        return topBar;
    }

    private JPanel createChatPanel() {
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBackground(chatTheme.CARD_BG);
        chatArea = chatTheme.createChatPane();
        chatPanel.add(chatTheme.wrapScroll(chatArea), BorderLayout.CENTER);
        return chatPanel;
    }

    private JPanel createOnlineUsersPanel() {
        JPanel usersPanel = new JPanel(new BorderLayout());
        usersPanel.setBackground(chatTheme.SIDEBAR_BG);
        usersPanel.setPreferredSize(new Dimension(200, 0));
        usersPanel.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, chatTheme.BORDER));

        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(chatTheme.SIDEBAR_BG);
        headerPanel.setBorder(new EmptyBorder(15, 15, 10, 15));

        JLabel headerLabel = new JLabel("用户列表");
        headerLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        headerLabel.setForeground(chatTheme.TEXT_DARK);
        headerPanel.add(headerLabel, BorderLayout.WEST);

        userCountLabel = new JLabel("1人在线");
        userCountLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        userCountLabel.setForeground(chatTheme.TEXT_GRAY);
        headerPanel.add(userCountLabel, BorderLayout.EAST);

        usersPanel.add(headerPanel, BorderLayout.NORTH);

        userListModel = new DefaultListModel<>();
        userList = new JList<>(userListModel);
        userList.setBackground(chatTheme.SIDEBAR_BG);
        userList.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        userList.setForeground(chatTheme.TEXT_DARK);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        userList.setCellRenderer(new UserListCellRenderer());
        userList.setFixedCellHeight(40);
        userList.setToolTipText("右键发起私聊，双击直接打开");

        usersPanel.add(chatTheme.wrapScroll(userList), BorderLayout.CENTER);

        userList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { showUserPopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { showUserPopup(e); }
            @Override
            public void mouseClicked(MouseEvent e) {
                // 双击直接开私聊：看到未读红点的人第一反应就是双击，
                // 只做右键入口会让红点变成点不开的摆设
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    UserEntry entry = entryAt(e.getPoint());
                    if (entry != null && !entry.name.equals(nickname)) {
                        openPrivateChat(entry.name);
                    }
                }
            }
        });

        return usersPanel;
    }

    private JPanel createInputPanel() {
        JPanel inputPanel = new JPanel(new BorderLayout(8, 0));
        inputPanel.setBackground(chatTheme.CARD_BG);
        inputPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, chatTheme.BORDER),
                new EmptyBorder(15, 20, 15, 20)
        ));

        JPanel buttonGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        buttonGroup.setOpaque(false);
        emojiButton = chatTheme.createIconButton("😊", "表情");
        emojiButton.addActionListener(e -> emojiPopup.show(emojiButton, 0, emojiButton.getHeight()));
        buttonGroup.add(emojiButton);
        inputPanel.add(buttonGroup, BorderLayout.WEST);

        inputField = chatTheme.createInputField();
        inputField.addActionListener(e -> sendMessage());
        inputPanel.add(inputField, BorderLayout.CENTER);

        JButton sendButton = chatTheme.createStyledButton("发送", chatTheme.PRIMARY,
                Color.WHITE, chatTheme.PRIMARY_HOVER);
        sendButton.setPreferredSize(new Dimension(80, 40));
        sendButton.addActionListener(e -> sendMessage());
        inputPanel.add(sendButton, BorderLayout.EAST);

        return inputPanel;
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

    // ===== 公共历史 =====

    private void beginHistoryLoad() {
        historyLoaded = false;
        pendingPublic.clear();
        client.requestPublicHistory();
        client.requestUnread();

        historyTimeout = new Timer(HISTORY_TIMEOUT_MS, e -> {
            if (!historyLoaded) {
                finishHistoryLoad();
            }
        });
        historyTimeout.setRepeats(false);
        historyTimeout.start();
    }

    private void finishHistoryLoad() {
        if (historyTimeout != null) {
            historyTimeout.stop();
        }
        for (Object[] m : pendingPublic) {
            chatTheme.appendMessage(chatArea, (String) m[0], (Long) m[1], (String) m[2],
                    ((String) m[0]).equals(nickname));
        }
        pendingPublic.clear();
        historyLoaded = true;
        chatTheme.appendSystemMessage(chatArea, "欢迎 " + nickname + " 加入聊天室！");
        chatTheme.appendSystemMessage(chatArea, "您已连接到服务器 " + serverIP + ":" + serverPort);
    }

    private void confirmClearPublic() {
        int ok = JOptionPane.showConfirmDialog(this,
                "确定要清空公共聊天室的全部聊天记录吗？\n此操作会删除所有人的公共聊天历史，不可恢复！",
                "清空确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            client.clearPublicHistory();
        }
    }

    // ===== 私聊窗口管理 =====

    /**
     * 打开（或前置已打开的）与某人的私聊窗口。
     * 必须在 EDT 上调用——窗口创建可能由用户点击触发，也可能由收到消息触发，
     * 统一在 EDT 上做才不会为同一个人建出两个窗口。
     */
    private privateChatUI openPrivateChat(String peer) {
        privateChatUI window = privateWindows.get(peer);
        if (window == null || !window.isDisplayable()) {
            window = new privateChatUI(nickname, peer, onlineUsers.contains(peer), client, this);
            privateWindows.put(peer, window);
            window.setVisible(true);
        } else {
            window.setState(Frame.NORMAL);
            window.toFront();
        }
        // 打开即视为已读
        clearUnread(peer);
        window.focusInput();
        return window;
    }

    /** 私聊窗口关闭时的回调，必须把映射摘掉，否则后续消息会被渲染进不可见的窗口 */
    void onPrivateWindowClosed(String peer) {
        privateWindows.remove(peer);
    }

    private void closeAllPrivateWindows() {
        for (privateChatUI w : new ArrayList<>(privateWindows.values())) {
            w.dispose();
        }
        privateWindows.clear();
    }

    private void setUnread(String peer, int count) {
        if (count <= 0) {
            unreadCounts.remove(peer);
        } else {
            unreadCounts.put(peer, count);
        }
        // UserEntry 对象本身没变，JList 不会自动重绘，必须显式触发
        userList.repaint();
    }

    private void clearUnread(String peer) {
        if (unreadCounts.remove(peer) != null) {
            userList.repaint();
        }
    }

    // ===== 用户列表 =====

    /** 取鼠标位置对应的用户条目，点在空白处返回 null */
    private UserEntry entryAt(Point p) {
        int index = userList.locationToIndex(p);
        if (index < 0) {
            return null;
        }
        // locationToIndex 对列表下方的空白区域也会返回最后一项，必须再验一次边界，
        // 否则右键空白处会误操作到最后一个用户
        Rectangle bounds = userList.getCellBounds(index, index);
        if (bounds == null || !bounds.contains(p)) {
            return null;
        }
        return userListModel.get(index);
    }

    /** 用户列表右键菜单：所有人都能发私聊，踢出/封禁仅管理员可见 */
    private void showUserPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        UserEntry entry = entryAt(e.getPoint());
        if (entry == null || entry.name.equals(nickname)) {
            return; // 空白处或自己，不弹菜单
        }
        userList.setSelectedValue(entry, false);

        JPopupMenu menu = new JPopupMenu();

        JMenuItem pmItem = new JMenuItem("发送私聊");
        pmItem.addActionListener(ev -> openPrivateChat(entry.name));
        menu.add(pmItem);

        if ("admin".equals(role)) {
            menu.addSeparator();

            JMenuItem kickItem = new JMenuItem("踢出用户");
            kickItem.addActionListener(ev -> client.kick(entry.name));

            JMenuItem banItem = new JMenuItem("封禁用户（删除账号）");
            banItem.addActionListener(ev -> {
                int ok = JOptionPane.showConfirmDialog(this,
                        "确定要封禁并删除用户「" + entry.name + "」吗？\n"
                                + "此操作将从数据库删除该账号及其全部私聊记录，不可恢复！",
                        "封禁确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ok == JOptionPane.YES_OPTION) {
                    client.ban(entry.name);
                }
            });
            menu.add(kickItem);
            menu.add(banItem);
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
        userCountLabel.setText(onlineUsers.size() + "人在线 · 共"
                + (onlineUsers.size() + offlineUsers.size()) + "人");
        // 同步刷新已打开私聊窗口里显示的对方在线状态
        for (Map.Entry<String, privateChatUI> e : privateWindows.entrySet()) {
            e.getValue().setPeerOnline(onlineUsers.contains(e.getKey()));
        }
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

    /** 未读数红点徽标 */
    private static class UnreadBadge extends JLabel {
        UnreadBadge() {
            setFont(new Font("Dialog", Font.BOLD, 10));
            setForeground(Color.WHITE);
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(chatTheme.UNREAD_RED);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 用户列表渲染器：头像 + 名字 + 未读徽标 + 在线状态点 */
    private class UserListCellRenderer extends JPanel implements ListCellRenderer<UserEntry> {
        private final JLabel avatarLabel;
        private final JLabel nameLabel;
        private final JLabel statusLabel;
        private final UnreadBadge badge;

        UserListCellRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(5, 10, 5, 10));
            setOpaque(true);

            avatarLabel = new JLabel();
            avatarLabel.setPreferredSize(new Dimension(28, 28));
            avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
            avatarLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
            avatarLabel.setForeground(Color.WHITE);
            avatarLabel.setOpaque(true);

            nameLabel = new JLabel();
            nameLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            nameLabel.setForeground(chatTheme.TEXT_DARK);

            badge = new UnreadBadge();

            statusLabel = new JLabel("●");
            statusLabel.setFont(new Font("Dialog", Font.PLAIN, 10));
            statusLabel.setForeground(chatTheme.ONLINE_GREEN);

            JPanel rightGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            rightGroup.setOpaque(false);
            rightGroup.add(badge);
            rightGroup.add(statusLabel);

            JPanel centerPanel = new JPanel(new BorderLayout(0, 2));
            centerPanel.setOpaque(false);
            centerPanel.add(nameLabel, BorderLayout.CENTER);
            centerPanel.add(rightGroup, BorderLayout.EAST);

            add(avatarLabel, BorderLayout.WEST);
            add(centerPanel, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends UserEntry> list, UserEntry value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            if (value != null) {
                avatarLabel.setText(value.name.substring(0, 1).toUpperCase());
                avatarLabel.setBackground(chatTheme.getColorForUser(value.name));
                nameLabel.setText(value.name);
                statusLabel.setForeground(value.online ? chatTheme.ONLINE_GREEN : chatTheme.OFFLINE_GRAY);
                nameLabel.setForeground(value.online ? chatTheme.TEXT_DARK : chatTheme.OFFLINE_TEXT);

                Integer unread = unreadCounts.get(value.name);
                if (unread != null && unread > 0) {
                    String text = unread > 99 ? "99+" : String.valueOf(unread);
                    badge.setText(text);
                    int width = Math.max(18, 10 + text.length() * 7);
                    badge.setPreferredSize(new Dimension(width, 16));
                    badge.setVisible(true);
                } else {
                    badge.setVisible(false);
                }
            }
            setBackground(isSelected ? new Color(235, 238, 250) : chatTheme.SIDEBAR_BG);
            return this;
        }
    }

    private void showExitConfirmation() {
        int result = JOptionPane.showConfirmDialog(this, "确定要退出聊天室吗？", "退出确认",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            closeAllPrivateWindows();
            client.logout();
            dispose();
            System.exit(0);
        }
    }

    /** 连接终止（被踢/被封禁/断线）后的统一收尾：关掉全部窗口并退出进程 */
    private void shutdownAfter(String title, String reason) {
        disconnectedNotified = true;
        chatTheme.appendSystemMessage(chatArea, "⚠️ " + reason);
        JOptionPane.showMessageDialog(this, reason, title, JOptionPane.WARNING_MESSAGE);
        closeAllPrivateWindows();
        dispose();
        System.exit(0);
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
        SwingUtilities.invokeLater(() -> chatTheme.appendSystemMessage(chatArea, content));
    }

    @Override
    public void onRole(String role) {
        this.role = role;
        SwingUtilities.invokeLater(() -> clearPublicButton.setVisible("admin".equals(role)));
    }

    @Override
    public void onKicked(String reason) {
        SwingUtilities.invokeLater(() -> shutdownAfter("已被踢出", reason));
    }

    @Override
    public void onBanned(String reason) {
        SwingUtilities.invokeLater(() -> shutdownAfter("已被封禁", reason));
    }

    @Override
    public void onChatMessage(String sender, long timestamp, String content) {
        SwingUtilities.invokeLater(() -> {
            if (!historyLoaded) {
                pendingPublic.add(new Object[]{sender, timestamp, content});
                return;
            }
            chatTheme.appendMessage(chatArea, sender, timestamp, content, sender.equals(nickname));
        });
    }

    @Override
    public void onPublicHistoryBegin() {
        SwingUtilities.invokeLater(() -> {
            chatArea.setText("");
            historyLoaded = false;
        });
    }

    @Override
    public void onPublicHistoryItem(String sender, long timestamp, String content) {
        SwingUtilities.invokeLater(() ->
                chatTheme.appendMessage(chatArea, sender, timestamp, content, sender.equals(nickname)));
    }

    @Override
    public void onPublicHistoryEnd() {
        SwingUtilities.invokeLater(this::finishHistoryLoad);
    }

    @Override
    public void onPublicCleared(String operator) {
        SwingUtilities.invokeLater(() -> {
            chatArea.setText("");
            chatTheme.appendSystemMessage(chatArea,
                    "公共聊天记录已被管理员「" + operator + "」清空");
        });
    }

    @Override
    public void onPrivateMessage(String peer, String sender, long timestamp,
                                 int unread, String content) {
        SwingUtilities.invokeLater(() -> {
            privateChatUI window = privateWindows.get(peer);
            boolean windowOpen = window != null && window.isDisplayable();
            if (windowOpen) {
                window.appendMessage(sender, timestamp, content);
                if (!sender.equals(nickname)) {
                    // 窗口开着就当即读掉，服务端那份未读标记也要同步清掉
                    client.markPrivateRead(peer);
                    clearUnread(peer);
                }
                return;
            }
            if (sender.equals(nickname)) {
                return; // 自己发出的回显，窗口却已关闭，无须提示
            }
            // 未读数直接用服务端给的权威值，不做本地自增，
            // 免得和随后到达的 UNREAD 汇总互相覆盖
            setUnread(peer, unread);
        });
    }

    @Override
    public void onPrivateHistoryBegin(String peer) {
        SwingUtilities.invokeLater(() -> {
            privateChatUI w = privateWindows.get(peer);
            if (w != null) {
                w.onHistoryBegin();
            }
        });
    }

    @Override
    public void onPrivateHistoryItem(String peer, String sender, long timestamp, String content) {
        SwingUtilities.invokeLater(() -> {
            privateChatUI w = privateWindows.get(peer);
            if (w != null) {
                w.onHistoryItem(sender, timestamp, content);
            }
        });
    }

    @Override
    public void onPrivateHistoryEnd(String peer) {
        SwingUtilities.invokeLater(() -> {
            privateChatUI w = privateWindows.get(peer);
            if (w != null) {
                w.onHistoryEnd();
            }
            clearUnread(peer);
        });
    }

    @Override
    public void onPrivateCleared(String peer) {
        SwingUtilities.invokeLater(() -> {
            privateChatUI w = privateWindows.get(peer);
            if (w != null) {
                w.onCleared();
            }
        });
    }

    @Override
    public void onPrivateFail(String peer, String reason) {
        SwingUtilities.invokeLater(() -> {
            privateChatUI w = privateWindows.get(peer);
            if (w != null) {
                w.markPeerGone(reason);
            } else {
                JOptionPane.showMessageDialog(this, reason, "私聊失败", JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    @Override
    public void onUnreadSummary(Map<String, Integer> counts) {
        SwingUtilities.invokeLater(() -> {
            // 取较大值而不是直接覆盖：这份汇总是服务端查询那一刻的快照，
            // 查询之后、响应到达之前收到的私聊已经通过 PMMSG 把徽标更新过了，
            // 直接覆盖会把那部分未读抹掉
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (privateWindows.containsKey(e.getKey())) {
                    continue; // 窗口开着的会话已经读过了，快照是过期数据
                }
                unreadCounts.merge(e.getKey(), e.getValue(), Math::max);
            }
            userList.repaint();
        });
    }

    @Override
    public void onUserList(String[] users) {
        SwingUtilities.invokeLater(() -> {
            onlineUsers.clear();
            java.util.Collections.addAll(onlineUsers, users);
            refreshUserList();
        });
    }

    @Override
    public void onOfflineUsers(String[] users) {
        SwingUtilities.invokeLater(() -> {
            offlineUsers.clear();
            java.util.Collections.addAll(offlineUsers, users);
            refreshUserList();
        });
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (disconnectedNotified) {
                return;
            }
            connectionStatusLabel.setText("● 已断开");
            connectionStatusLabel.setForeground(chatTheme.DANGER);
            shutdownAfter("连接断开", "与服务器的连接已断开：" + reason);
        });
    }
}
