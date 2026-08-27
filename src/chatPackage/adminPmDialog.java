package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * 管理员查看私聊记录弹窗：输入两个用户名，查看两人之间的完整私聊记录。
 *
 * 记录通过 chatClient 回调（onAdminPmHistoryBegin/Item/End）回放，由 clientChatUI
 * 路由进本弹窗（onHistoryBegin/Item/End）；失败走 onFail。
 *
 * 线程约定：所有公开方法都在 EDT 上调用（父窗口回调已切好线程）。
 */
public class adminPmDialog extends JFrame {

    private final chatClient client;

    private final JTextField userAField;
    private final JTextField userBField;
    private final JButton queryButton;
    private final DefaultListModel<String> resultModel = new DefaultListModel<>();
    private final JList<String> resultList;
    private final JLabel statusLabel;

    public adminPmDialog(Component parent, chatClient client) {
        super("查看私聊记录（管理员）");
        this.client = client;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(560, 520);
        setLocationRelativeTo(parent);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(chatTheme.BG_LIGHT);

        // 顶部：两个用户名输入框 + 查询按钮
        JPanel queryBar = new JPanel(new BorderLayout(8, 0));
        queryBar.setBackground(chatTheme.CARD_BG);
        queryBar.setBorder(new EmptyBorder(12, 15, 10, 15));

        JPanel fields = new JPanel(new GridLayout(1, 2, 8, 0));
        fields.setOpaque(false);
        userAField = chatTheme.createInputField();
        userAField.putClientProperty("JTextField.placeholderText", "用户A用户名");
        userBField = chatTheme.createInputField();
        userBField.putClientProperty("JTextField.placeholderText", "用户B用户名");
        fields.add(userAField);
        fields.add(userBField);
        queryBar.add(fields, BorderLayout.CENTER);

        queryButton = chatTheme.createStyledButton("查询", chatTheme.PRIMARY,
                Color.WHITE, chatTheme.PRIMARY_HOVER);
        queryButton.setPreferredSize(new Dimension(64, 34));
        queryButton.addActionListener(e -> doQuery());
        // 回车查询
        userBField.addActionListener(e -> doQuery());
        queryBar.add(queryButton, BorderLayout.EAST);

        main.add(queryBar, BorderLayout.NORTH);

        // 中部：结果列表
        resultList = new JList<>(resultModel);
        resultList.setFont(chatTheme.getChatFont(13));
        resultList.setForeground(chatTheme.TEXT_DARK);
        resultList.setBackground(chatTheme.CARD_BG);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setBorder(new EmptyBorder(5, 15, 5, 15));
        main.add(chatTheme.wrapScroll(resultList), BorderLayout.CENTER);

        // 底部：状态栏 + 关闭
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBackground(chatTheme.CARD_BG);
        bottom.setBorder(new EmptyBorder(10, 15, 12, 15));

        statusLabel = new JLabel("输入两个用户名，查询他们之间的私聊记录");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statusLabel.setForeground(chatTheme.TEXT_GRAY);
        bottom.add(statusLabel, BorderLayout.WEST);

        JButton closeButton = chatTheme.createStyledButton("关闭",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        closeButton.addActionListener(e -> dispose());
        JPanel closeWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        closeWrap.setOpaque(false);
        closeWrap.add(closeButton);
        bottom.add(closeWrap, BorderLayout.EAST);

        main.add(bottom, BorderLayout.SOUTH);
        add(main);
        setVisible(true);
    }

    private void doQuery() {
        String u1 = userAField.getText().trim();
        String u2 = userBField.getText().trim();
        if (u1.isEmpty() || u2.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请填写两个用户名", "查看私聊记录",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (u1.equals(u2)) {
            JOptionPane.showMessageDialog(this, "不能查询同一个人", "查看私聊记录",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        queryButton.setEnabled(false); // 查询期间禁用防连点，END/FAIL 时恢复
        statusLabel.setText("查询中...");
        client.requestAdminPmHistory(u1, u2);
    }

    // ===== 回放回调（EDT） =====

    public void onHistoryBegin() {
        resultModel.clear();
        statusLabel.setText("查询中...");
    }

    /** 追加一条记录：图片消息显示为 [图片]，不把 Base64 原文灌进列表（同 searchDialog 处理） */
    public void onHistoryItem(long timestamp, String sender, String content) {
        if (chatTheme.isImageContent(content)) {
            content = "[图片]";
        }
        resultModel.addElement("[" + chatTheme.formatTime(timestamp) + "] " + sender + ": " + content);
        resultList.ensureIndexIsVisible(resultModel.size() - 1);
    }

    public void onHistoryEnd() {
        queryButton.setEnabled(true);
        statusLabel.setText("共 " + resultModel.size() + " 条记录");
    }

    /** 查询失败 */
    public void onFail(String reason) {
        queryButton.setEnabled(true);
        statusLabel.setText("查询失败：" + reason);
    }
}
