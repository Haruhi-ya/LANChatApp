package chatPackage;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 主聊天窗口：公共聊天室 + 用户列表 + 私聊入口。
 *
 * 本类是 chatClient 唯一的监听器（chatClient 是单监听器设计，setListener 为整体替换），
 * 因此所有消息都先到这里，再由这里路由到对应的私聊窗口。privateChatUI 不允许自己
 * 注册监听器，否则主窗口会立刻失去全部回调。
 *
 * 消息渲染使用 bubbleChatList（微信风格气泡）。线程约定：chatClient 的回调发生在网络
 * 接收线程上，所有回调实现都用 SwingUtilities.invokeLater 切回 EDT 之后再碰界面。
 */
public class clientChatUI extends JFrame implements chatClient.Listener, bubbleChatList.MenuHandler {

    /** 公共历史加载超时，服务端中途断开时不至于让界面一直空着 */
    private static final int HISTORY_TIMEOUT_MS = 10_000;

    // UI组件
    private bubbleChatList chatList;
    private JTextField inputField;
    private JButton emojiButton;
    private JButton clearPublicButton;
    private JTextField searchField;
    private JLabel connectionStatusLabel;
    private JLabel userCountLabel;
    private chatTheme.AvatarLabel selfAvatarLabel;

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
    private final List<Object[]> pendingPublic = new ArrayList<>(); // {sender, msgId, ts, content, img}
    private final Set<String> recalledDuringLoad = ConcurrentHashMap.newKeySet();
    private Timer historyTimeout;

    // 被 @ 提醒
    private Timer flashTimer;
    private final String normalTitle;

    // 搜索
    private searchDialog activeSearchDialog;

    // 检测内容里是否 @ 了自己（Unicode 词边界：Java 的 \w 不含中文，会误报「张三」对「张」）
    private final Pattern mentionPattern;

    private JPopupMenu emojiPopup;

    public clientChatUI(String nickname, String serverIP, int serverPort, chatClient client) {
        this.nickname = nickname;
        this.serverIP = serverIP;
        this.serverPort = serverPort;
        this.client = client;
        this.normalTitle = "局域网聊天室 - " + nickname;
        this.mentionPattern = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote("@" + nickname)
                + "(?![\\p{L}\\p{N}_])");
        // 注册消息回调（回调发生在接收线程，实现里统一用 invokeLater 切回 EDT 更新界面）
        // 注意：登录验证已在登录界面完成，这里只接管消息渲染，不能再调用 login()
        client.setListener(this);
        // 头像按需拉取的唯一来源：气泡/列表绘制发现未缓存时通过它发 GETAVATAR。
        // 本进程只有一个聊天窗口，这里单次注册；私聊窗口复用，不重复注册。
        chatTheme.setAvatarProvider(name -> client.getAvatar(name));
        initUI();
        beginHistoryLoad();
    }

    private void initUI() {
        setTitle(normalTitle);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1150, 750);
        setMinimumSize(new Dimension(1000, 650));
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBackground(chatTheme.BG_LIGHT);
        mainPanel.add(createTopBar(), BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createChatPanel(), createOnlineUsersPanel());
        splitPane.setDividerLocation(900); // 1150 宽下聊天区约 900、侧边栏约 250
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
            @Override
            public void windowActivated(WindowEvent e) {
                stopFlash();
            }
            @Override
            public void windowClosed(WindowEvent e) {
                stopFlash();
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

        selfAvatarLabel = chatTheme.createAvatarLabel(nickname, 30, 14);
        selfAvatarLabel.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
        selfAvatarLabel.setToolTipText("点击更换头像");
        selfAvatarLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        selfAvatarLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    showAvatarMenu();
                }
            }
        });
        rightPanel.add(selfAvatarLabel);

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

        chatList = new bubbleChatList(this);

        // 搜索栏
        JPanel searchBar = new JPanel(new BorderLayout(8, 0));
        searchBar.setBackground(chatTheme.CARD_BG);
        searchBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, chatTheme.BORDER),
                new EmptyBorder(8, 12, 8, 12)
        ));
        searchField = chatTheme.createInputField();
        searchField.putClientProperty("JTextField.placeholderText", "搜索公共聊天记录，回车执行");
        searchField.addActionListener(e -> doSearch());
        searchBar.add(searchField, BorderLayout.CENTER);
        JButton searchButton = chatTheme.createStyledButton("搜索", chatTheme.PRIMARY,
                Color.WHITE, chatTheme.PRIMARY_HOVER);
        searchButton.setPreferredSize(new Dimension(64, 34));
        searchButton.addActionListener(e -> doSearch());
        searchBar.add(searchButton, BorderLayout.EAST);

        chatPanel.add(searchBar, BorderLayout.NORTH);
        chatPanel.add(chatList, BorderLayout.CENTER);
        return chatPanel;
    }

    private void doSearch() {
        String keyword = searchField.getText().trim();
        if (keyword.isEmpty()) {
            return;
        }
        // 每次搜索换新弹窗：同一时刻只有一个公共搜索在进行，旧弹窗直接关掉
        if (activeSearchDialog != null) {
            activeSearchDialog.dispose();
        }
        activeSearchDialog = new searchDialog(this, "公共聊天记录搜索：\"" + keyword + "\"");
        client.searchPublic(keyword);
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
                // 双击直接开私聊：看到未读红点的人第一反应就是双击
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
        JButton imageButton = chatTheme.createIconButton("📷", "发送图片");
        imageButton.addActionListener(e -> chooseAndSendImage());
        buttonGroup.add(imageButton);
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

    // ===== 头像与图片发送 =====

    private void showAvatarMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem changeItem = new JMenuItem("更换头像");
        changeItem.addActionListener(e -> chooseAndSetAvatar());
        menu.add(changeItem);
        JMenuItem removeItem = new JMenuItem("移除头像");
        removeItem.addActionListener(e -> client.setAvatar(""));
        menu.add(removeItem);
        menu.show(selfAvatarLabel, 0, selfAvatarLabel.getHeight());
    }

    private void chooseAndSetAvatar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择头像图片（JPG / PNG，128px 内）");
        chooser.setFileFilter(new FileNameExtensionFilter("图片文件 (jpg, png)", "jpg", "jpeg", "png"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (file.length() > chatTheme.MAX_AVATAR_BYTES) {
            JOptionPane.showMessageDialog(this, "头像文件过大（上限 128KB），请换一张更小的图片",
                    "更换头像", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            byte[] bytes = chatTheme.buildAvatarBytes(file);
            client.setAvatar(Base64.getEncoder().encodeToString(bytes));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "读取图片失败：" + ex.getMessage(),
                    "更换头像", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void chooseAndSendImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要发送的图片（JPG / PNG，≤1MB）");
        chooser.setFileFilter(new FileNameExtensionFilter("图片文件 (jpg, png)", "jpg", "jpeg", "png"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            // 图片消息与文本走同一条协议管道（[IMG]Base64），由服务端广播回来统一渲染
            client.sendMessage(chatTheme.buildImageMessage(chooser.getSelectedFile()));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "发送失败：" + ex.getMessage(),
                    "发送图片", JOptionPane.WARNING_MESSAGE);
        }
    }

    // ===== 公共历史 =====

    private void beginHistoryLoad() {
        historyLoaded = false;
        pendingPublic.clear();
        recalledDuringLoad.clear();
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
            appendPublicMessage((String) m[0], (String) m[1], (Long) m[2], (String) m[3], (Image) m[4]);
        }
        pendingPublic.clear();
        recalledDuringLoad.clear();
        historyLoaded = true;
        chatList.addSystem("欢迎 " + nickname + " 加入聊天室！", false);
        chatList.addSystem("您已连接到服务器 " + serverIP + ":" + serverPort, false);
    }

    private void confirmClearPublic() {
        int ok = JOptionPane.showConfirmDialog(this,
                "确定要清空公共聊天室的全部聊天记录吗？\n此操作会删除所有人的公共聊天历史，不可恢复！",
                "清空确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            client.clearPublicHistory();
        }
    }

    // ===== 气泡：构造与撤回 =====

    /** 构造一个公共消息气泡：图片消息带解码图，文本消息带 @ 高亮判断 */
    private bubbleChatList.BubbleMsg makePublicBubble(String sender, String msgId,
                                                      long timestamp, String content, Image img) {
        boolean mine = sender.equals(nickname);
        boolean canRecall = !msgId.isEmpty() && (mine || "admin".equals(role));
        if (chatTheme.isImageContent(content)) {
            // 图片消息不参与 @ 高亮；解码失败用占位图，避免 Base64 当文本渲染
            return bubbleChatList.bubbleImage(sender, msgId, timestamp, content,
                    mine, canRecall, img != null ? img : chatTheme.placeholderImage());
        }
        String mention = mentionPattern.matcher(content).find() ? nickname : null;
        return bubbleChatList.bubble(sender, msgId, timestamp, content, mine, canRecall, mention);
    }

    private void appendPublicMessage(String sender, String msgId, long timestamp,
                                     String content, Image img) {
        // 历史加载期间被撤回的消息不渲染（撤回事件可能早于/穿插在历史条目中间到达）
        if (recalledDuringLoad.contains(msgId)) {
            return;
        }
        chatList.addMessage(makePublicBubble(sender, msgId, timestamp, content, img));
    }

    @Override
    public boolean canRecall(bubbleChatList.BubbleMsg msg) {
        return msg.canRecall;
    }

    @Override
    public void onRecall(bubbleChatList.BubbleMsg msg) {
        client.recall(msg.msgId);
    }

    // ===== 私聊窗口管理 =====

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
            return;
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
        for (Map.Entry<String, privateChatUI> e : privateWindows.entrySet()) {
            e.getValue().setPeerOnline(onlineUsers.contains(e.getKey()));
        }
    }

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
        private final chatTheme.AvatarLabel avatarLabel;
        private final JLabel nameLabel;
        private final JLabel statusLabel;
        private final UnreadBadge badge;

        UserListCellRenderer() {
            setLayout(new BorderLayout(10, 0));
            setBorder(new EmptyBorder(5, 10, 5, 10));
            setOpaque(true);

            avatarLabel = chatTheme.createAvatarLabel("", 28, 12);

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
                avatarLabel.setUsername(value.name);
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

    // ===== 被 @ 提醒 =====

    private void flashAttention() {
        chatTheme.playBeep();
        if (flashTimer != null) {
            flashTimer.stop();
        }
        setState(Frame.NORMAL);
        toFront();
        final int[] toggles = {10}; // 10 次 × 500ms = 5 秒
        flashTimer = new Timer(500, null);
        flashTimer.addActionListener(e -> {
            if (toggles[0]-- <= 0) {
                stopFlash();
                return;
            }
            setTitle(toggles[0] % 2 == 0 ? "【有人@你】" + normalTitle : normalTitle);
        });
        flashTimer.start();
    }

    private void stopFlash() {
        if (flashTimer != null) {
            flashTimer.stop();
            flashTimer = null;
        }
        setTitle(normalTitle);
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
        chatList.addSystem("⚠️ " + reason, false);
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
        SwingUtilities.invokeLater(() -> chatList.addSystem(content, false));
    }

    @Override
    public void onRole(String role) {
        this.role = role;
        // setListener 会补发缓存的角色，回调可能先于 UI 构造完成到达（本类构造器还没返回）
        SwingUtilities.invokeLater(() -> {
            if (clearPublicButton != null) {
                clearPublicButton.setVisible("admin".equals(role));
            }
        });
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
    public void onChatMessage(String sender, String msgId, long timestamp, String content) {
        // 图片解码放在接收线程（invokeLater 之前），大图解码不卡 EDT
        Image img = chatTheme.decodeImageMessage(content);
        SwingUtilities.invokeLater(() -> {
            if (!historyLoaded) {
                pendingPublic.add(new Object[]{sender, msgId, timestamp, content, img});
                return;
            }
            appendPublicMessage(sender, msgId, timestamp, content, img);
        });
    }

    @Override
    public void onPublicHistoryBegin() {
        SwingUtilities.invokeLater(() -> {
            chatList.clear();
            historyLoaded = false;
        });
    }

    @Override
    public void onPublicHistoryItem(String sender, String msgId, long timestamp, String content) {
        Image img = chatTheme.decodeImageMessage(content);
        SwingUtilities.invokeLater(() -> appendPublicMessage(sender, msgId, timestamp, content, img));
    }

    @Override
    public void onPublicHistoryEnd() {
        SwingUtilities.invokeLater(this::finishHistoryLoad);
    }

    @Override
    public void onPublicCleared(String operator) {
        SwingUtilities.invokeLater(() -> {
            chatList.clear();
            chatList.addSystem("公共聊天记录已被管理员「" + operator + "」清空", false);
        });
    }

    @Override
    public void onRecalled(String msgId, String byWho) {
        SwingUtilities.invokeLater(() -> {
            boolean mine = byWho.equals(nickname);
            if (!historyLoaded) {
                // 撤回事件可能与历史回放交错：记下来，渲染历史/pending 时跳过该消息
                recalledDuringLoad.add(msgId);
                for (int i = pendingPublic.size() - 1; i >= 0; i--) {
                    if (msgId.equals(pendingPublic.get(i)[1])) {
                        pendingPublic.remove(i);
                    }
                }
                return;
            }
            chatList.recallMessage(msgId, bubbleChatList.recallText(byWho, mine));
        });
    }

    @Override
    public void onRecallFail(String msgId, String reason) {
        SwingUtilities.invokeLater(() ->
                JOptionPane.showMessageDialog(this, reason, "撤回失败", JOptionPane.WARNING_MESSAGE));
    }

    @Override
    public void onAttention(String from) {
        SwingUtilities.invokeLater(() -> {
            chatList.addSystem("🔔 " + from + " 在公共频道 @ 了你", false);
            flashAttention();
        });
    }

    @Override
    public void onSearchResultBegin(String peer) {
        // 弹窗在点击搜索时已经建好，这里无需动作
    }

    @Override
    public void onSearchResultItem(String peer, String msgId, String sender,
                                   long timestamp, String content) {
        SwingUtilities.invokeLater(() -> {
            if ("PUBLIC".equals(peer)) {
                if (activeSearchDialog != null) {
                    activeSearchDialog.addResult(sender, timestamp, content);
                }
            } else {
                privateChatUI w = privateWindows.get(peer);
                if (w != null) {
                    w.onSearchResultItem(sender, timestamp, content);
                }
            }
        });
    }

    @Override
    public void onSearchResultEnd(String peer) {
        SwingUtilities.invokeLater(() -> {
            if ("PUBLIC".equals(peer)) {
                if (activeSearchDialog != null) {
                    activeSearchDialog.finish();
                }
            } else {
                privateChatUI w = privateWindows.get(peer);
                if (w != null) {
                    w.onSearchResultEnd();
                }
            }
        });
    }

    @Override
    public void onSearchFail(String peer, String reason) {
        SwingUtilities.invokeLater(() -> {
            if ("PUBLIC".equals(peer)) {
                if (activeSearchDialog != null) {
                    activeSearchDialog.fail(reason);
                }
            } else {
                privateChatUI w = privateWindows.get(peer);
                if (w != null) {
                    w.onSearchFail(reason);
                }
            }
        });
    }

    @Override
    public void onPrivateMessage(String peer, String sender, String msgId, long timestamp,
                                 int unread, String content) {
        SwingUtilities.invokeLater(() -> {
            privateChatUI window = privateWindows.get(peer);
            boolean windowOpen = window != null && window.isDisplayable();
            if (windowOpen) {
                window.appendMessage(sender, msgId, timestamp, content);
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
    public void onPrivateHistoryItem(String peer, String sender, String msgId,
                                     long timestamp, String content) {
        SwingUtilities.invokeLater(() -> {
            privateChatUI w = privateWindows.get(peer);
            if (w != null) {
                w.onHistoryItem(sender, msgId, timestamp, content);
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
    public void onPrivateRecalled(String peer, String msgId, String byWho) {
        SwingUtilities.invokeLater(() -> {
            privateChatUI w = privateWindows.get(peer);
            if (w != null) {
                w.onRecalled(msgId, byWho);
            }
            // 窗口没开：对方撤了未读消息，红点按服务端随后补发的 UNREAD 汇总纠正
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
            if (userList != null) {
                userList.repaint();
            }
        });
    }

    @Override
    public void onUserList(String[] users) {
        SwingUtilities.invokeLater(() -> {
            if (userList == null) {
                return; // 缓存列表补发可能先于 UI 构造完成到达
            }
            onlineUsers.clear();
            Collections.addAll(onlineUsers, users);
            refreshUserList();
        });
    }

    @Override
    public void onOfflineUsers(String[] users) {
        SwingUtilities.invokeLater(() -> {
            if (userList == null) {
                return; // 同上
            }
            offlineUsers.clear();
            Collections.addAll(offlineUsers, users);
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

    // ===== 头像回调 =====

    @Override
    public void onAvatar(String name, byte[] data) {
        // ImageIO.read 在接收线程做，大图解码不卡 EDT
        BufferedImage bmp = null;
        if (data != null) {
            try {
                bmp = ImageIO.read(new ByteArrayInputStream(data));
            } catch (IOException e) {
                bmp = null; // 损坏数据按无头像处理（chatTheme 落负缓存，不再重拉）
            }
        }
        final BufferedImage img = bmp;
        SwingUtilities.invokeLater(() -> {
            if (chatTheme.cacheAvatar(name, img)) {
                refreshAllAvatars();
            }
        });
    }

    @Override
    public void onAvatarChanged(String name) {
        SwingUtilities.invokeLater(() -> {
            chatTheme.onAvatarChanged(name);
            refreshAllAvatars(); // 重绘触发未缓存头像按需重拉
        });
    }

    @Override
    public void onAvatarResult(boolean success, String reason) {
        SwingUtilities.invokeLater(() -> {
            if (success) {
                JOptionPane.showMessageDialog(this, "头像已更新", "更换头像",
                        JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "更换头像失败：" + reason, "更换头像",
                        JOptionPane.WARNING_MESSAGE);
            }
        });
    }

    /** 头像缓存变化后统一重绘所有显示头像的地方（气泡列表 / 用户列表 / 顶栏 / 私聊窗口） */
    private void refreshAllAvatars() {
        chatList.repaintTable();
        userList.repaint();
        if (selfAvatarLabel != null) {
            selfAvatarLabel.repaint();
        }
        for (privateChatUI w : privateWindows.values()) {
            w.refreshAvatars();
        }
    }
}
