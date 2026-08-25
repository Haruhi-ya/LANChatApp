package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 一对一私聊窗口。每个会话对方对应一个窗口，由 clientChatUI 创建和持有。
 *
 * ⚠️ 本类绝对不能调用 client.setListener()。
 * chatClient 是单监听器设计，setListener 是整体替换而不是追加——私聊窗口一旦把自己
 * 注册进去，主窗口会立刻失去全部回调（在线用户列表、被踢、被封禁、断线提示统统失灵），
 * 而且这个问题编译期发现不了，只有运行时才会暴露。
 * 所有私聊消息都必须经由 clientChatUI 的监听器路由转发进来，这正是协议里
 * PMMSG 第一个字段带「会话对方」的意义。
 *
 * 线程约定：所有公开方法都必须在 EDT 上调用，clientChatUI 负责用 invokeLater 切线程。
 */
public class privateChatUI extends JFrame {

    /** 历史加载超时：服务端中途断开时不至于让窗口永远卡在加载中 */
    private static final int HISTORY_TIMEOUT_MS = 10_000;

    private final String myName;
    private final String peer;
    private final chatClient client;
    private final clientChatUI parent;

    private JTextPane chatArea;
    private JTextField inputField;
    private JButton clearButton;
    private JLabel statusLabel;
    private JPopupMenu emojiPopup;

    /**
     * 历史是否加载完毕。加载期间到达的实时消息先存进 pending，等 PMHISTEND 到达后
     * 再按顺序补渲染。
     *
     * 必须缓冲的原因：服务端的历史回放和实时广播跑在两个不同的线程上，写的却是同一个
     * socket。PrintWriter 只保证单行不被撕裂，不保证跨行的先后顺序。所以对方在我拉取
     * 历史的同时发来消息时，PMMSG 可能插在 PMHISTITEM 序列中间（顺序错乱），
     * 也可能它已经在服务端查询的快照里、又被实时推了一次（重复显示）。
     */
    private boolean historyLoaded;
    private final List<Object[]> pending = new ArrayList<>();
    private Timer historyTimeout;

    public privateChatUI(String myName, String peer, boolean peerOnline,
                         chatClient client, clientChatUI parent) {
        this.myName = myName;
        this.peer = peer;
        this.client = client;
        this.parent = parent;
        initUI();
        setPeerOnline(peerOnline);
        beginHistoryLoad();
    }

    private void initUI() {
        setTitle("与 " + peer + " 的私聊");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(520, 560);
        setMinimumSize(new Dimension(420, 420));
        setLocationByPlatform(true);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(chatTheme.BG_LIGHT);
        main.add(createTopBar(), BorderLayout.NORTH);

        chatArea = chatTheme.createChatPane();
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBackground(chatTheme.CARD_BG);
        chatPanel.add(chatTheme.wrapScroll(chatArea), BorderLayout.CENTER);
        main.add(chatPanel, BorderLayout.CENTER);

        main.add(createInputPanel(), BorderLayout.SOUTH);
        add(main);

        emojiPopup = chatTheme.createEmojiPopup(inputField);

        // 窗口关闭时必须通知主窗口移除映射，否则主窗口会以为窗口还开着，
        // 把后续消息渲染进这个已经不可见的面板、还顺手标记成已读——消息就此静默蒸发
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (historyTimeout != null) {
                    historyTimeout.stop();
                }
                parent.onPrivateWindowClosed(peer);
            }
        });
    }

    private JPanel createTopBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBackground(chatTheme.CARD_BG);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, chatTheme.BORDER),
                new EmptyBorder(12, 16, 12, 16)
        ));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(chatTheme.createAvatarLabel(peer, 32, 15));

        JLabel nameLabel = new JLabel(peer);
        nameLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 15));
        nameLabel.setForeground(chatTheme.TEXT_DARK);
        left.add(nameLabel);

        statusLabel = new JLabel();
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        left.add(statusLabel);
        bar.add(left, BorderLayout.WEST);

        clearButton = chatTheme.createStyledButton("清空聊天记录",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        clearButton.setPreferredSize(new Dimension(110, 32));
        clearButton.addActionListener(e -> confirmClear());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(clearButton);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    private JPanel createInputPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(chatTheme.CARD_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, chatTheme.BORDER),
                new EmptyBorder(12, 16, 12, 16)
        ));

        JButton emojiButton = chatTheme.createIconButton("😊", "表情");
        emojiButton.addActionListener(e -> emojiPopup.show(emojiButton, 0, emojiButton.getHeight()));
        JPanel leftGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        leftGroup.setOpaque(false);
        leftGroup.add(emojiButton);
        panel.add(leftGroup, BorderLayout.WEST);

        inputField = chatTheme.createInputField();
        inputField.addActionListener(e -> sendMessage());
        panel.add(inputField, BorderLayout.CENTER);

        JButton sendButton = chatTheme.createStyledButton("发送", chatTheme.PRIMARY,
                Color.WHITE, chatTheme.PRIMARY_HOVER);
        sendButton.setPreferredSize(new Dimension(80, 40));
        sendButton.addActionListener(e -> sendMessage());
        panel.add(sendButton, BorderLayout.EAST);

        return panel;
    }

    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        // 只发给服务端，由服务端回显后统一渲染，保证时间戳与数据库里的一致
        client.sendPrivate(peer, text);
        inputField.setText("");
        inputField.requestFocusInWindow();
    }

    private void confirmClear() {
        int ok = JOptionPane.showConfirmDialog(this,
                "确定要清空与「" + peer + "」的聊天记录吗？\n"
                        + "只会清空你这边的记录，对方那边不受影响。",
                "清空确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok == JOptionPane.YES_OPTION) {
            client.clearPrivateHistory(peer);
        }
    }

    // ===== 历史加载 =====

    private void beginHistoryLoad() {
        historyLoaded = false;
        pending.clear();
        // 加载期间不允许清空：DELETE 会先执行，而随后到达的历史条目又会把刚清掉的
        // 记录重新填回界面，看起来像是没清干净
        clearButton.setEnabled(false);
        client.requestPrivateHistory(peer);

        historyTimeout = new Timer(HISTORY_TIMEOUT_MS, e -> {
            if (!historyLoaded) {
                chatTheme.appendSystemMessage(chatArea, "历史记录加载超时");
                finishHistoryLoad();
            }
        });
        historyTimeout.setRepeats(false);
        historyTimeout.start();
    }

    /** 收到 PMHISTBEGIN：清空面板准备接收历史 */
    public void onHistoryBegin() {
        chatArea.setText("");
        historyLoaded = false;
    }

    /** 收到 PMHISTITEM：逐条渲染历史 */
    public void onHistoryItem(String sender, long timestamp, String content) {
        chatTheme.appendMessage(chatArea, sender, timestamp, content, sender.equals(myName));
    }

    /** 收到 PMHISTEND：补渲染加载期间缓冲的实时消息 */
    public void onHistoryEnd() {
        finishHistoryLoad();
    }

    private void finishHistoryLoad() {
        if (historyTimeout != null) {
            historyTimeout.stop();
        }
        for (Object[] m : pending) {
            chatTheme.appendMessage(chatArea, (String) m[0], (Long) m[1], (String) m[2],
                    ((String) m[0]).equals(myName));
        }
        pending.clear();
        historyLoaded = true;
        clearButton.setEnabled(true);
    }

    // ===== 消息与状态 =====

    /** 收到一条实时私聊消息（历史还没加载完时先缓冲，避免与回放交错） */
    public void appendMessage(String sender, long timestamp, String content) {
        if (!historyLoaded) {
            pending.add(new Object[]{sender, timestamp, content});
            return;
        }
        chatTheme.appendMessage(chatArea, sender, timestamp, content, sender.equals(myName));
    }

    public void appendSystemMessage(String message) {
        chatTheme.appendSystemMessage(chatArea, message);
    }

    /** 自己这边的记录已清空 */
    public void onCleared() {
        chatArea.setText("");
        chatTheme.appendSystemMessage(chatArea, "聊天记录已清空（仅清空你这边，对方不受影响）");
    }

    /** 刷新对方的在线状态，由主窗口在用户列表变化时调用 */
    public void setPeerOnline(boolean online) {
        statusLabel.setText(online ? "● 在线" : "○ 离线");
        statusLabel.setForeground(online ? chatTheme.ONLINE_GREEN : chatTheme.OFFLINE_GRAY);
    }

    /** 对方账号已注销（被封禁）：禁用输入，窗口保留供查看 */
    public void markPeerGone(String reason) {
        statusLabel.setText("○ 已注销");
        statusLabel.setForeground(chatTheme.DANGER);
        inputField.setEnabled(false);
        chatTheme.appendSystemMessage(chatArea, reason);
    }

    public void focusInput() {
        inputField.requestFocusInWindow();
    }
}
