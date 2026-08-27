package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.Set;

/**
 * 添加好友弹窗：按用户名关键词搜索用户 → 选中后发送好友申请。
 *
 * 与 searchDialog 同款结构：结果回放通过 chatClient 回调（onUserSearch*）到达，
 * 由 clientChatUI 路由进本弹窗（onSearchBegin/Item/End/Fail）。
 * 申请结果（onRequestSent/onRequestFail）同样由父窗口回调进来。
 *
 * 线程约定：所有公开方法都在 EDT 上调用（父窗口回调已切好线程）。
 */
public class addFriendDialog extends JFrame {

    private final clientChatUI parent;
    private final chatClient client;

    private final DefaultListModel<String> resultModel = new DefaultListModel<>();
    private final JList<String> resultList;
    private final JLabel statusLabel;
    private final JButton addButton;

    public addFriendDialog(clientChatUI parent, chatClient client) {
        super("添加好友");
        this.parent = parent;
        this.client = client;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(460, 480);
        setLocationRelativeTo(parent);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(chatTheme.BG_LIGHT);

        // 顶部：搜索框 + 搜索按钮
        JPanel searchBar = new JPanel(new BorderLayout(8, 0));
        searchBar.setBackground(chatTheme.CARD_BG);
        searchBar.setBorder(new EmptyBorder(12, 15, 10, 15));

        JTextField keywordField = chatTheme.createInputField();
        keywordField.putClientProperty("JTextField.placeholderText", "输入用户名关键词搜索");
        keywordField.addActionListener(e -> doSearch(keywordField.getText()));
        searchBar.add(keywordField, BorderLayout.CENTER);

        JButton searchButton = chatTheme.createStyledButton("搜索", chatTheme.PRIMARY,
                Color.WHITE, chatTheme.PRIMARY_HOVER);
        searchButton.setPreferredSize(new Dimension(64, 34));
        searchButton.addActionListener(e -> doSearch(keywordField.getText()));
        searchBar.add(searchButton, BorderLayout.EAST);

        main.add(searchBar, BorderLayout.NORTH);

        // 中部：结果列表
        resultList = new JList<>(resultModel);
        resultList.setFont(chatTheme.getChatFont(13));
        resultList.setForeground(chatTheme.TEXT_DARK);
        resultList.setBackground(chatTheme.CARD_BG);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setBorder(new EmptyBorder(5, 15, 5, 15));
        // 双击直接发申请
        resultList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    String sel = resultList.getSelectedValue();
                    if (sel != null && !sel.contains("（已是好友）")) {
                        sendRequest(sel);
                    }
                }
            }
        });
        main.add(chatTheme.wrapScroll(resultList), BorderLayout.CENTER);

        // 底部：按钮 + 状态栏
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(chatTheme.CARD_BG);
        bottom.setBorder(new EmptyBorder(10, 15, 12, 15));

        statusLabel = new JLabel("输入关键词搜索用户");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statusLabel.setForeground(chatTheme.TEXT_GRAY);
        bottom.add(statusLabel, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);

        addButton = chatTheme.createStyledButton("发送好友申请",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        addButton.addActionListener(e -> {
            String sel = resultList.getSelectedValue();
            if (sel != null && !sel.contains("（已是好友）")) {
                sendRequest(sel);
            }
        });
        buttons.add(addButton);

        JButton closeButton = chatTheme.createStyledButton("关闭",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        closeButton.addActionListener(e -> dispose());
        buttons.add(closeButton);

        bottom.add(buttons, BorderLayout.EAST);
        main.add(bottom, BorderLayout.SOUTH);

        add(main);
        setVisible(true);
    }

    /** 从父窗口拿好友集合（构造时 UI 已就绪，父窗口引用安全） */
    private Set<String> getUserFriends() {
        return parent.getFriends();
    }

    private void doSearch(String keyword) {
        if (keyword.trim().isEmpty()) {
            return;
        }
        client.searchUsers(keyword.trim());
    }

    private void sendRequest(String username) {
        client.sendFriendRequest(username);
    }

    // ===== 结果回放回调（EDT） =====

    public void onSearchBegin() {
        resultModel.clear();
        statusLabel.setText("搜索中...");
    }

    /** 追加一条搜索结果；已是好友的项做标记（不可重复添加），自己不出现在结果里（服务端已排除） */
    public void onSearchItem(String username) {
        boolean friend = getUserFriends().contains(username);
        resultModel.addElement(friend ? username + "（已是好友）" : username);
    }

    public void onSearchEnd() {
        statusLabel.setText("搜索完成，共 " + resultModel.size() + " 个用户");
    }

    public void onSearchFail(String reason) {
        statusLabel.setText("搜索失败：" + reason);
    }

    /** 申请已发送 */
    public void onRequestSent(String target) {
        statusLabel.setText("已向「" + target + "」发送好友申请，等待对方同意");
        JOptionPane.showMessageDialog(this, "好友申请已发送，等待对方同意", "添加好友",
                JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    /** 申请发送失败 */
    public void onRequestFail(String reason) {
        JOptionPane.showMessageDialog(this, reason, "添加好友", JOptionPane.WARNING_MESSAGE);
    }
}
