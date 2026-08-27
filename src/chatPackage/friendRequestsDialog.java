package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * 好友申请列表弹窗：展示待处理的申请者，可同意 / 拒绝 / 刷新。
 *
 * 申请列表通过 chatClient 回调（onFriendRequestsBegin/Item/End）回放，由 clientChatUI
 * 路由进本弹窗（onRequestsBegin/Item/End）。每次打开和收到新申请推送时都会 refresh()
 * 重新拉取，保证列表与红点一致。
 *
 * 线程约定：所有公开方法都在 EDT 上调用（父窗口回调已切好线程）。
 */
public class friendRequestsDialog extends JFrame {

    private final chatClient client;

    private final DefaultListModel<String> requestModel = new DefaultListModel<>();
    private final JList<String> requestList;
    private final JLabel statusLabel;

    public friendRequestsDialog(clientChatUI parent, chatClient client) {
        super("好友申请");
        this.client = client;

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(420, 420);
        setLocationRelativeTo(parent);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(chatTheme.BG_LIGHT);

        // 顶部状态栏
        statusLabel = new JLabel("加载中...");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        statusLabel.setForeground(chatTheme.TEXT_GRAY);
        statusLabel.setBorder(new EmptyBorder(12, 15, 10, 15));
        main.add(statusLabel, BorderLayout.NORTH);

        // 中部：申请者列表
        requestList = new JList<>(requestModel);
        requestList.setFont(chatTheme.getChatFont(13));
        requestList.setForeground(chatTheme.TEXT_DARK);
        requestList.setBackground(chatTheme.CARD_BG);
        requestList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestList.setBorder(new EmptyBorder(5, 15, 5, 15));
        main.add(chatTheme.wrapScroll(requestList), BorderLayout.CENTER);

        // 底部按钮
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bottom.setBackground(chatTheme.CARD_BG);
        bottom.setBorder(new EmptyBorder(10, 15, 12, 15));

        JButton acceptButton = chatTheme.createStyledButton("同意选中", chatTheme.PRIMARY,
                Color.WHITE, chatTheme.PRIMARY_HOVER);
        acceptButton.addActionListener(e -> {
            String sel = requestList.getSelectedValue();
            if (sel != null) {
                client.acceptFriendRequest(sel);
                // 同意后申请行已删：从列表移除，红点由父窗口 FRIENDREQLIST 回放/推送纠正
                requestModel.removeElement(sel);
            }
        });
        bottom.add(acceptButton);

        JButton rejectButton = chatTheme.createStyledButton("拒绝选中",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        rejectButton.addActionListener(e -> {
            String sel = requestList.getSelectedValue();
            if (sel != null) {
                client.rejectFriendRequest(sel);
                requestModel.removeElement(sel);
            }
        });
        bottom.add(rejectButton);

        JButton refreshButton = chatTheme.createStyledButton("刷新",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        refreshButton.addActionListener(e -> refresh());
        bottom.add(refreshButton);

        JButton closeButton = chatTheme.createStyledButton("关闭",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        closeButton.addActionListener(e -> dispose());
        bottom.add(closeButton);

        main.add(bottom, BorderLayout.SOUTH);
        add(main);
        setVisible(true);
    }

    /** 重新拉取最新申请列表（打开时 / 收到新申请推送时调用） */
    public void refresh() {
        client.requestFriendRequests();
    }

    // ===== 回放回调（EDT） =====

    public void onRequestsBegin() {
        requestModel.clear();
        statusLabel.setText("加载中...");
    }

    public void onRequestsItem(String from) {
        requestModel.addElement(from);
    }

    public void onRequestsEnd() {
        statusLabel.setText(requestModel.isEmpty() ? "暂无待处理的好友申请"
                : "共 " + requestModel.size() + " 条待处理申请");
    }
}
