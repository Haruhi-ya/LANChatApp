package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * 聊天记录搜索结果弹窗。
 *
 * 只做展示 + 复制，不做「定位到原消息」——搜索范围就是当前会话，无处可跳；
 * 界面模型只持有最近 500 条，更早的结果也定位不了。如需定位，后续可加
 * 「加载该消息前后文」的分页协议，是纯增量改动。
 *
 * 线程约定：所有方法都在 EDT 上调用。
 */
public class searchDialog extends JFrame {

    private final DefaultListModel<String> resultModel = new DefaultListModel<>();
    private final JList<String> resultList;
    private final JLabel statusLabel;

    public searchDialog(Component parent, String title) {
        super(title);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(560, 420);
        setLocationRelativeTo(parent);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(chatTheme.BG_LIGHT);

        // 顶部状态栏
        statusLabel = new JLabel("搜索中...");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statusLabel.setForeground(chatTheme.TEXT_GRAY);
        statusLabel.setBorder(new EmptyBorder(10, 15, 10, 15));
        main.add(statusLabel, BorderLayout.NORTH);

        // 结果列表
        resultList = new JList<>(resultModel);
        resultList.setFont(chatTheme.getChatFont(13));
        resultList.setForeground(chatTheme.TEXT_DARK);
        resultList.setBackground(chatTheme.CARD_BG);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setBorder(new EmptyBorder(5, 15, 5, 15));
        main.add(chatTheme.wrapScroll(resultList), BorderLayout.CENTER);

        // 底部按钮
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bottom.setBackground(chatTheme.CARD_BG);
        bottom.setBorder(new EmptyBorder(10, 15, 12, 15));

        JButton copyButton = chatTheme.createStyledButton("复制选中结果",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        copyButton.addActionListener(e -> {
            String selected = resultList.getSelectedValue();
            if (selected != null) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(selected), null);
            }
        });
        bottom.add(copyButton);

        JButton closeButton = chatTheme.createStyledButton("关闭",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        closeButton.addActionListener(e -> dispose());
        bottom.add(closeButton);

        main.add(bottom, BorderLayout.SOUTH);
        add(main);
        setVisible(true);
    }

    /** 追加一条搜索结果 */
    public void addResult(String sender, long timestamp, String content) {
        resultModel.addElement("[" + chatTheme.formatTime(timestamp) + "] " + sender + ": " + content);
        resultList.ensureIndexIsVisible(resultModel.size() - 1);
    }

    /** 搜索结束 */
    public void finish() {
        statusLabel.setText("搜索完成，共 " + resultModel.size() + " 条结果");
    }

    /** 搜索失败：显示原因 */
    public void fail(String reason) {
        statusLabel.setText("搜索失败：" + reason);
    }
}
