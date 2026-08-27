package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * 修改密码弹窗。
 *
 * 三个密码框（旧密码 / 新密码 / 确认新密码），本地校验通过后提交给服务器
 * （chatClient.changePassword），结果由异步回调 {@link #onResult} 回填——
 * 所以这个弹窗是非模态的：服务器响应到达时（接收线程 → EDT）窗口可能还开着，
 * 结果必须能写进当前窗口，而不是等一个阻塞对话框。
 *
 * 线程约定：onResult 只允许在 EDT 上调用（clientChatUI 回调里已切好线程）。
 */
public class changePasswordDialog extends JDialog {

    private final chatClient client;
    private final JPasswordField oldField;
    private final JPasswordField newField;
    private final JPasswordField confirmField;
    private final JButton submitButton;

    public changePasswordDialog(Component parent, chatClient client) {
        super(SwingUtilities.getWindowAncestor(parent), "修改密码", Dialog.ModalityType.APPLICATION_MODAL);
        this.client = client;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setSize(380, 330);
        setLocationRelativeTo(parent);

        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(chatTheme.CARD_BG);

        // 标题
        JLabel title = chatTheme.createMixedTextLabel("🔒 修改密码");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 16));
        title.setForeground(chatTheme.TEXT_DARK);
        title.setBorder(new EmptyBorder(18, 20, 8, 20));
        main.add(title, BorderLayout.NORTH);

        // 表单：三个密码框
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(chatTheme.CARD_BG);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 20, 4, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;

        oldField = createPasswordField();
        newField = createPasswordField();
        confirmField = createPasswordField();

        gbc.gridy = 0;
        form.add(createLabel("旧密码"), gbc);
        gbc.gridy = 1;
        form.add(oldField, gbc);
        gbc.gridy = 2;
        form.add(createLabel("新密码（≤64个字符）"), gbc);
        gbc.gridy = 3;
        form.add(newField, gbc);
        gbc.gridy = 4;
        form.add(createLabel("确认新密码"), gbc);
        gbc.gridy = 5;
        form.add(confirmField, gbc);

        main.add(form, BorderLayout.CENTER);

        // 底部按钮
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        bottom.setBackground(chatTheme.CARD_BG);
        bottom.setBorder(new EmptyBorder(8, 15, 14, 15));

        submitButton = chatTheme.createStyledButton("确认修改",
                chatTheme.PRIMARY, Color.WHITE, chatTheme.PRIMARY_HOVER);
        submitButton.addActionListener(e -> submit());
        bottom.add(submitButton);

        JButton cancelButton = chatTheme.createStyledButton("取消",
                new Color(235, 238, 245), chatTheme.TEXT_DARK, new Color(220, 224, 235));
        cancelButton.addActionListener(e -> dispose());
        bottom.add(cancelButton);

        main.add(bottom, BorderLayout.SOUTH);
        add(main);

        // 回车触发提交
        getRootPane().setDefaultButton(submitButton);
        setVisible(true);
    }

    private JLabel createLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        label.setForeground(chatTheme.TEXT_GRAY);
        return label;
    }

    /** 密码框样式与登录窗口保持一致（圆角边框 + ● 掩码） */
    private JPasswordField createPasswordField() {
        JPasswordField field = new JPasswordField();
        field.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        field.setForeground(chatTheme.TEXT_DARK);
        field.setCaretColor(chatTheme.PRIMARY);
        field.setBackground(chatTheme.BG_LIGHT);
        field.setEchoChar('●');
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 215, 230), 1, true),
                BorderFactory.createEmptyBorder(9, 14, 9, 14)
        ));
        return field;
    }

    /** 本地校验通过后提交给服务器（提交期间禁用按钮防连点） */
    private void submit() {
        String oldPass = new String(oldField.getPassword()).trim();
        String newPass = new String(newField.getPassword()).trim();
        String confirmPass = new String(confirmField.getPassword()).trim();
        if (oldPass.isEmpty() || newPass.isEmpty() || confirmPass.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请填写完整的密码信息", "修改密码",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (newPass.length() > 64) {
            JOptionPane.showMessageDialog(this, "新密码过长（上限64个字符）", "修改密码",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!newPass.equals(confirmPass)) {
            JOptionPane.showMessageDialog(this, "两次输入的新密码不一致", "修改密码",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (oldPass.equals(newPass)) {
            JOptionPane.showMessageDialog(this, "新密码不能与旧密码相同", "修改密码",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        submitButton.setEnabled(false);
        client.changePassword(oldPass, newPass);
    }

    /**
     * 服务器返回修改结果（EDT 调用）。
     * 成功提示后关闭窗口；失败恢复按钮并弹警告。
     */
    public void onResult(boolean success, String reason) {
        submitButton.setEnabled(true);
        if (success) {
            JOptionPane.showMessageDialog(this, "密码修改成功，下次登录请使用新密码",
                    "修改密码", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } else {
            JOptionPane.showMessageDialog(this, reason, "修改密码", JOptionPane.WARNING_MESSAGE);
        }
    }
}
