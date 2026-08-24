package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

public class chatEntryUI extends JFrame {

    private JTextField nicknameField;
    private JTextField ipField;
    private JTextField portField;
    private JButton enterButton;
    private JButton cancelButton;

    private static final Color PRIMARY = new Color(99, 132, 255);
    private static final Color PRIMARY_HOVER = new Color(75, 108, 235);
    private static final Color BG_LIGHT = new Color(245, 247, 252);
    private static final Color CARD_BG = Color.WHITE;
    private static final Color TEXT_DARK = new Color(44, 52, 74);
    private static final Color TEXT_GRAY = new Color(140, 149, 168);
    private static final Color CANCEL_BG = new Color(235, 238, 245);
    private static final Color CANCEL_HOVER = new Color(220, 224, 235);

    public chatEntryUI() {
        initUI();
    }

    private void initUI() {
        setTitle("局域网聊天室");
        setResizable(false);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel wrapper = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint gp = new GradientPaint(0, 0, new Color(230, 235, 255),
                        getWidth(), getHeight(), new Color(248, 240, 255));
                g2.setPaint(gp);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        wrapper.setBorder(new EmptyBorder(40, 50, 40, 50));

        JPanel card = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CARD_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.setColor(new Color(220, 225, 240));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(30, 40, 30, 40));

        GridBagConstraints gbc = new GridBagConstraints();

        JLabel titleLabel = new JLabel("👋 欢迎进入聊天室");
        titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 22));
        titleLabel.setForeground(TEXT_DARK);
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(0, 0, 6, 0);
        gbc.anchor = GridBagConstraints.CENTER;
        card.add(titleLabel, gbc);

        JLabel subtitleLabel = new JLabel("请输入连接信息，开始畅聊吧");
        subtitleLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        subtitleLabel.setForeground(TEXT_GRAY);
        subtitleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gbc.gridy = 1;
        gbc.insets = new Insets(0, 0, 25, 0);
        card.add(subtitleLabel, gbc);

        JSeparator sep = new JSeparator() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(225, 228, 240));
                g2.drawLine(0, 0, getWidth(), 0);
                g2.dispose();
            }
        };
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 20, 0);
        card.add(sep, gbc);

        // 昵称标签
        JLabel nameLabel = new JLabel("昵称");
        nameLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        nameLabel.setForeground(TEXT_DARK);
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(0, 0, 8, 0);
        card.add(nameLabel, gbc);

        // 昵称输入框
        nicknameField = createStyledTextField("请输入您的昵称");
        gbc.gridy = 4;
        gbc.insets = new Insets(0, 0, 20, 0);
        card.add(nicknameField, gbc);

        // IP地址标签
        JLabel ipLabel = new JLabel("服务器IP地址");
        ipLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        ipLabel.setForeground(TEXT_DARK);
        gbc.gridy = 5;
        gbc.insets = new Insets(0, 0, 8, 0);
        card.add(ipLabel, gbc);

        // IP地址输入框
        ipField = createStyledTextField("例如：192.168.1.100");
        gbc.gridy = 6;
        gbc.insets = new Insets(0, 0, 20, 0);
        card.add(ipField, gbc);

        // 端口标签
        JLabel portLabel = new JLabel("端口号");
        portLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        portLabel.setForeground(TEXT_DARK);
        gbc.gridy = 7;
        gbc.insets = new Insets(0, 0, 8, 0);
        card.add(portLabel, gbc);

        // 端口输入框
        portField = createStyledTextField("例如：8080");
        gbc.gridy = 8;
        gbc.insets = new Insets(0, 0, 25, 0);
        card.add(portField, gbc);

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttonPanel.setOpaque(false);

        enterButton = createStyledButton("进入聊天", PRIMARY, Color.WHITE, PRIMARY_HOVER);
        cancelButton = createStyledButton("取消", CANCEL_BG, TEXT_DARK, CANCEL_HOVER);

        buttonPanel.add(enterButton);
        buttonPanel.add(cancelButton);

        gbc.gridy = 9;
        gbc.gridwidth = 2;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;
        gbc.insets = new Insets(0, 0, 5, 0);
        card.add(buttonPanel, gbc);

        wrapper.add(card);
        add(wrapper);

        enterButton.addActionListener(e -> onEnter());
        cancelButton.addActionListener(e -> onCancel());
        nicknameField.addActionListener(e -> ipField.requestFocusInWindow());
        ipField.addActionListener(e -> portField.requestFocusInWindow());
        portField.addActionListener(e -> onEnter());
        getRootPane().setDefaultButton(enterButton);

        pack();
        setLocationRelativeTo(null);
    }

    private JTextField createStyledTextField(String placeholder) {
        JTextField textField = new JTextField(20);
        textField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        textField.setForeground(TEXT_DARK);
        textField.setCaretColor(PRIMARY);
        textField.setBackground(BG_LIGHT);
        textField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 215, 230), 1, true),
                BorderFactory.createEmptyBorder(10, 14, 10, 14)
        ));
        textField.setCaretPosition(0);
        textField.setToolTipText(placeholder);

        // 添加占位符效果
        textField.setText(placeholder);
        textField.setForeground(TEXT_GRAY);

        textField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                if (textField.getText().equals(placeholder)) {
                    textField.setText("");
                    textField.setForeground(TEXT_DARK);
                }
                textField.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(PRIMARY, 2, true),
                        BorderFactory.createEmptyBorder(9, 13, 9, 13)
                ));
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                if (textField.getText().isEmpty()) {
                    textField.setText(placeholder);
                    textField.setForeground(TEXT_GRAY);
                }
                textField.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(210, 215, 230), 1, true),
                        BorderFactory.createEmptyBorder(10, 14, 10, 14)
                ));
            }
        });

        return textField;
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
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 12, 12));
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
        button.setPreferredSize(new Dimension(130, 42));
        button.setOpaque(false);

        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) { button.repaint(); }
            @Override
            public void mouseExited(MouseEvent e) { button.repaint(); }
        });

        return button;
    }

    public String getNickname() {
        String nickname = nicknameField.getText().trim();
        // 如果还是占位符文本，返回空字符串
        if (nickname.equals("请输入您的昵称")) {
            return "";
        }
        return nickname;
    }

    public String getServerIP() {
        String ip = ipField.getText().trim();
        if (ip.equals("例如：192.168.1.100")) {
            return "";
        }
        return ip;
    }

    public String getPort() {
        String port = portField.getText().trim();
        if (port.equals("例如：8080")) {
            return "";
        }
        return port;
    }

    private void onEnter() {
        String nickname = getNickname();
        String ip = getServerIP();
        String port = getPort();

        // 验证昵称
        if (nickname.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入昵称", "提示",
                    JOptionPane.WARNING_MESSAGE);
            nicknameField.requestFocusInWindow();
            return;
        }

        // 验证IP地址
        if (ip.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入服务器IP地址", "提示",
                    JOptionPane.WARNING_MESSAGE);
            ipField.requestFocusInWindow();
            return;
        }

        // 验证IP格式
        if (!isValidIP(ip)) {
            JOptionPane.showMessageDialog(this, "请输入有效的IP地址格式", "提示",
                    JOptionPane.WARNING_MESSAGE);
            ipField.requestFocusInWindow();
            return;
        }

        // 验证端口
        if (port.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入端口号", "提示",
                    JOptionPane.WARNING_MESSAGE);
            portField.requestFocusInWindow();
            return;
        }

        // 验证端口格式和范围
        try {
            int portNum = Integer.parseInt(port);
            if (portNum < 1 || portNum > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "请输入有效的端口号（1-65535）", "提示",
                    JOptionPane.WARNING_MESSAGE);
            portField.requestFocusInWindow();
            return;
        }

        JOptionPane.showMessageDialog(this,
                "欢迎进入聊天室：" + nickname + "\n服务器：" + ip + ":" + port,
                "进入成功",
                JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    private boolean isValidIP(String ip) {
        // 简单的IP地址格式验证
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        for (String part : parts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private void onCancel() {
        dispose();
        System.exit(0);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new chatEntryUI().setVisible(true);
        });
    }
}