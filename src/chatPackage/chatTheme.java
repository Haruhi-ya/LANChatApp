package chatPackage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天界面共享的配色、字体和消息渲染工具。
 *
 * 主聊天窗口（clientChatUI）和私聊窗口（privateChatUI）用同一套视觉语言，
 * 相关代码集中在这里，避免两个窗口各维护一份渲染逻辑而慢慢走样。
 *
 * 登录窗口（chatEntryUI）不使用本类：登录卡片和聊天窗口是不同的视觉语境，
 * 强行合并只会扩大改动面而没有实际收益。
 *
 * 线程约定：所有方法都应在 Swing 事件分发线程（EDT）上调用。
 */
public final class chatTheme {

    private chatTheme() {
    }

    // ===== 配色 =====

    public static final Color PRIMARY = new Color(99, 132, 255);
    public static final Color PRIMARY_HOVER = new Color(75, 108, 235);
    public static final Color BG_LIGHT = new Color(245, 247, 252);
    public static final Color SIDEBAR_BG = new Color(248, 249, 252);
    public static final Color CARD_BG = Color.WHITE;
    public static final Color TEXT_DARK = new Color(44, 52, 74);
    public static final Color TEXT_GRAY = new Color(140, 149, 168);
    public static final Color ONLINE_GREEN = new Color(52, 199, 123);
    public static final Color OFFLINE_GRAY = new Color(170, 175, 190);
    public static final Color OFFLINE_TEXT = new Color(160, 165, 180);
    public static final Color SYSTEM_MSG_COLOR = new Color(150, 155, 170);
    public static final Color BORDER = new Color(220, 225, 235);
    public static final Color UNREAD_RED = new Color(240, 71, 71);
    public static final Color DANGER = new Color(220, 60, 60);

    /** 表情面板可选的表情 */
    public static final String[] EMOJIS = {"😀", "😂", "🤣", "😊", "😍", "🥰", "😘", "😎",
            "🤔", "🤗", "😅", "😉", "🙃", "😋", "😴", "🤯",
            "😇", "🥳", "😭", "😤", "👍", "👎", "👏", "🙏",
            "💪", "🤝", "❤️", "💔", "🎉", "✨", "🔥", "💯"};

    // ===== 字体 =====

    /**
     * 系统可用字体名缓存。
     *
     * GraphicsEnvironment.getAvailableFontFamilyNames() 会枚举系统全部字体，开销在
     * 几十毫秒量级。它原先被 appendMessage 每渲染一条消息就调用一次，属于高频路径上的
     * 浪费——这里只在首次调用时枚举一次。
     */
    private static volatile Set<String> availableFonts;

    public static boolean isFontAvailable(String fontName) {
        Set<String> fonts = availableFonts;
        if (fonts == null) {
            synchronized (chatTheme.class) {
                if (availableFonts == null) {
                    Set<String> names = new HashSet<>();
                    for (String name : GraphicsEnvironment.getLocalGraphicsEnvironment()
                            .getAvailableFontFamilyNames()) {
                        names.add(name.toLowerCase());
                    }
                    availableFonts = names;
                }
                fonts = availableFonts;
            }
        }
        return fonts.contains(fontName.toLowerCase());
    }

    /** 聊天正文字体：微软雅黑同时含中文和大部分 emoji 字形 */
    public static Font getChatFont(int size) {
        return new Font(isFontAvailable("Microsoft YaHei") ? "Microsoft YaHei" : "Dialog",
                Font.PLAIN, size);
    }

    /**
     * 纯 emoji 按钮用的字体。
     * 只用于不含中文的按钮——emoji 专用字体没有中文字形，拿去渲染中文会显示成方块。
     */
    public static Font getEmojiFont(int size) {
        String[] candidates = {"Segoe UI Emoji", "Noto Color Emoji", "Apple Color Emoji"};
        for (String name : candidates) {
            if (isFontAvailable(name)) {
                return new Font(name, Font.PLAIN, size);
            }
        }
        return new Font("Dialog", Font.PLAIN, size);
    }

    // ===== 用户配色 =====

    /**
     * 用户专属颜色：色相均匀分布的 16 种高区分度颜色。
     *
     * 早期实现拿 hashCode % 360 当色相，相近昵称的 hash 往往只差 1，算出的颜色几乎
     * 一模一样，看起来就像所有人被硬编码成了同一种颜色。改成按哈希稳定分配调色板槽位。
     *
     * 缓存是静态的，这样同一个用户在主窗口和各个私聊窗口里颜色始终一致。
     */
    private static final int USER_COLOR_COUNT = 16;
    private static final ConcurrentHashMap<String, Color> USER_COLORS = new ConcurrentHashMap<>();

    public static Color getColorForUser(String username) {
        return USER_COLORS.computeIfAbsent(username, name -> {
            int index = Math.floorMod(name.hashCode(), USER_COLOR_COUNT);
            float hue = index * (360.0f / USER_COLOR_COUNT);
            return Color.getHSBColor(hue / 360.0f, 0.7f, 0.85f);
        });
    }

    // ===== 时间格式 =====

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss");

    public static String formatTime(long timestamp) {
        return TIME_FORMAT.format(new Date(timestamp));
    }

    /** 把聊天面板套进无边框、滚动步进合适的滚动容器 */
    public static JScrollPane wrapScroll(Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return scroll;
    }

    // ===== 控件工厂 =====

    /** 圆角实心按钮 */
    public static JButton createStyledButton(String text, Color bg, Color fg, Color hoverBg) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getModel().isPressed() || getModel().isRollover() ? hoverBg : bg);
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

    /** 方形图标按钮（用于表情等纯符号按钮） */
    public static JButton createIconButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setFont(getEmojiFont(20));
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(40, 40));
        button.setBackground(CARD_BG);
        button.setBorder(BorderFactory.createLineBorder(BORDER, 1, true));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) { button.setBackground(BG_LIGHT); }
            @Override
            public void mouseExited(MouseEvent e) { button.setBackground(CARD_BG); }
        });
        return button;
    }

    /** 聊天输入框 */
    public static JTextField createInputField() {
        JTextField field = new JTextField();
        field.setFont(getChatFont(14));
        field.setForeground(TEXT_DARK);
        field.setBackground(BG_LIGHT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 215, 230), 1, true),
                BorderFactory.createEmptyBorder(10, 15, 10, 15)
        ));
        return field;
    }

    /** 创建表情选择弹窗，选中的表情会追加到 target 输入框 */
    public static JPopupMenu createEmojiPopup(JTextField target) {
        JPopupMenu popup = new JPopupMenu();
        JPanel panel = new JPanel(new GridLayout(4, 8, 5, 5));
        panel.setBackground(CARD_BG);
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        for (String emoji : EMOJIS) {
            JButton btn = new JButton(emoji);
            btn.setFont(getEmojiFont(20));
            btn.setPreferredSize(new Dimension(40, 40));
            btn.setBackground(CARD_BG);
            btn.setBorder(BorderFactory.createLineBorder(new Color(230, 233, 240), 1));
            btn.setFocusPainted(false);
            btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
            btn.addActionListener(e -> {
                target.setText(target.getText() + emoji);
                popup.setVisible(false);
                target.requestFocusInWindow();
            });
            btn.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) { btn.setBackground(BG_LIGHT); }
                @Override
                public void mouseExited(MouseEvent e) { btn.setBackground(CARD_BG); }
            });
            panel.add(btn);
        }
        popup.add(panel);
        return popup;
    }

    /** 圆形头像标签，底色是用户专属颜色，显示用户名首字母 */
    public static JLabel createAvatarLabel(String username, int size, int fontSize) {
        JLabel label = new JLabel();
        label.setPreferredSize(new Dimension(size, size));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setFont(new Font("Microsoft YaHei", Font.BOLD, fontSize));
        label.setForeground(Color.WHITE);
        label.setOpaque(true);
        label.setBackground(getColorForUser(username));
        label.setText(username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase());
        label.setToolTipText(username);
        return label;
    }

    // ===== 提示音 =====

    /**
     * 播放提示音（被 @ 提醒用）。
     *
     * WAV 在首次调用时程序化合成并缓存，不依赖任何外部资源文件。合成的是短促双音
     * 「滴——滴」，440Hz/880Hz 正弦波带线性衰减包络，音量压到 0.35 避免刺耳。
     * 整个播放路径 try/catch：无声卡/音频线不可用的环境下静默降级。
     */
    private static volatile Clip beepClip;

    public static void playBeep() {
        try {
            Clip clip = beepClip;
            if (clip == null) {
                synchronized (chatTheme.class) {
                    if (beepClip == null) {
                        beepClip = buildBeepClip();
                    }
                    clip = beepClip;
                }
            }
            if (clip != null) {
                clip.stop();
                clip.setFramePosition(0);
                clip.start();
            }
        } catch (Exception ignored) {
            // 音频不可用时静默降级，不影响主流程
        }
    }

    private static Clip buildBeepClip() throws LineUnavailableException {
        float sampleRate = 16000f;
        int toneMs = 120, gapMs = 60, silenceMs = 100;
        int total = (toneMs * 2 + gapMs + silenceMs) * 16; // 16 字节/毫秒（16kHz, 16bit 单声道）
        byte[] data = new byte[total];
        double[] freqs = {880.0, 880.0};

        int pos = 0;
        for (int i = 0; i < 2; i++) {
            for (int t = 0; t < toneMs * 16; t += 2, pos += 2) {
                double env = 1.0 - (t / 2) / (double) (toneMs * 16 / 2);
                short sample = (short) (Math.sin(2 * Math.PI * freqs[i] * (t / 2) / sampleRate)
                        * env * Short.MAX_VALUE * 0.35);
                data[pos] = (byte) (sample & 0xFF);
                data[pos + 1] = (byte) ((sample >> 8) & 0xFF);
            }
            pos += (i == 0 ? gapMs : silenceMs) * 16;
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
             javax.sound.sampled.AudioInputStream ais =
                     new javax.sound.sampled.AudioInputStream(bais, format, data.length / 2)) {
            Clip clip = AudioSystem.getClip();
            clip.open(ais);
            return clip;
        } catch (Exception e) {
            return null;
        }
    }
}
