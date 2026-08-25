package chatPackage;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Base64;
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

    // ===== 图片消息与头像 =====

    /** 图片消息内容前缀：content = IMG_PREFIX + Base64(图片字节)，协议命令本身零改动 */
    public static final String IMG_PREFIX = "[IMG]";

    /** 图片消息的图片大小上限（原始字节），超限客户端拒绝发送 */
    public static final long MAX_IMAGE_MESSAGE_BYTES = 1024 * 1024;

    /** 发送前图片长边压缩目标（像素），微信风格，控制传输体积 */
    public static final int IMAGE_MAX_EDGE = 1280;

    /** 头像图片大小上限（原始字节），超限服务端拒绝 */
    public static final long MAX_AVATAR_BYTES = 128 * 1024;

    /** 头像存储尺寸（像素），上传前压缩到该尺寸内 */
    public static final int AVATAR_MAX_EDGE = 128;

    /** 解码后图片的最大像素数（防御超大扁平图在渲染缩放时撑爆内存） */
    private static final long MAX_DECODED_PIXELS = 16_000_000L;

    private static volatile BufferedImage placeholderImage;

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

    /** 圆形头像标签：有头像画图片（圆形裁剪），无头像回退「底色 + 首字母」。自绘组件 */
    public static AvatarLabel createAvatarLabel(String username, int size, int fontSize) {
        return new AvatarLabel(username, size, fontSize);
    }

    /**
     * 自绘头像标签。原来用 setText/setBackground 的调用点改为 setUsername 后自绘：
     * 头像图片加载/变更后只需 repaint()，JLabel 的文本状态与头像绘制完全解耦。
     */
    public static class AvatarLabel extends JLabel {
        private final int size;
        private final int fontSize;
        private String username;

        AvatarLabel(String username, int size, int fontSize) {
            this.username = username;
            this.size = size;
            this.fontSize = fontSize;
            setPreferredSize(new Dimension(size, size));
            setHorizontalAlignment(SwingConstants.CENTER);
            setToolTipText(username);
        }

        public void setUsername(String username) {
            this.username = username;
            setToolTipText(username);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paintAvatar(g2, username, 0, 0, size, fontSize);
            g2.dispose();
        }
    }

    // ===== 头像：拉取、缓存与绘制 =====

    /**
     * 头像来源接口：由主聊天窗口在构造时注册（clientChatUI.setAvatarProvider）。
     * 头像数据按需拉取——绘制组件发现未缓存时通过 fetch 异步发起 GETAVATAR，
     * 响应到达后 cacheAvatar 写入缓存，再统一重绘。
     */
    public interface AvatarProvider {
        void fetch(String username);
    }

    /**
     * 头像缓存与版本管理。
     *
     * 版本号解决「快速连改两次头像」的竞态：每次变更（onAvatarChanged）版本 +1，
     * 触发拉取时记录当时的版本，响应到达时版本已变则丢弃（陈旧数据覆盖新头像）。
     *
     * 负缓存：版本存在但图片不存在 = 已拉取过且确实无头像。这样无头像用户不会被
     * 每次绘制反复 GETAVATAR（用户列表重绘会触发的拉取风暴）。
     */
    private static volatile AvatarProvider avatarProvider;
    private static final ConcurrentHashMap<String, BufferedImage> AVATAR_IMAGES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> AVATAR_VERSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> AVATAR_PENDING = new ConcurrentHashMap<>(); // 拉取中:用户名->发起时版本

    /** 注册头像来源（只由 clientChatUI 构造器调用一次） */
    public static void setAvatarProvider(AvatarProvider provider) {
        avatarProvider = provider;
    }

    /** 头像变更通知（AVATARCHG 到达）：清缓存并递增版本，旧响应从此作废 */
    public static void onAvatarChanged(String username) {
        AVATAR_VERSIONS.merge(username, 1L, Long::sum);
        AVATAR_IMAGES.remove(username);
        AVATAR_PENDING.remove(username);
    }

    /**
     * 写入拉取结果。bmp 为 null 表示「该用户确实没有头像」，只落版本负缓存。
     * 返回是否写入成功（false = 版本已过期，数据陈旧被丢弃）。
     */
    public static boolean cacheAvatar(String username, BufferedImage bmp) {
        Long pendingVersion = AVATAR_PENDING.remove(username);
        if (pendingVersion == null
                || pendingVersion != AVATAR_VERSIONS.getOrDefault(username, 0L)) {
            return false; // 拉取期间头像又变了，陈旧数据，丢弃
        }
        if (bmp != null) {
            AVATAR_IMAGES.put(username, bmp);
        }
        return true;
    }

    /**
     * 取用户头像原图。未缓存（含负缓存）时返回 null 并触发按需拉取：
     *  - 从未拉过 → 记录当前版本并发起 GETAVATAR
     *  - 负缓存（版本在、图不在）→ 不再重复拉取
     * 返回的图是 128px 原图，调用方按目标尺寸 drawImage 自行缩放。
     */
    public static BufferedImage getAvatarImage(String username) {
        Long version = AVATAR_VERSIONS.get(username);
        if (version == null) {
            // 首次见到这个用户：发起拉取，绘制端先画首字母占位
            long v = AVATAR_VERSIONS.computeIfAbsent(username, k -> 1L);
            AVATAR_PENDING.put(username, v);
            AvatarProvider p = avatarProvider;
            if (p != null) {
                p.fetch(username);
            }
            return null;
        }
        return AVATAR_IMAGES.get(username); // null = 负缓存（无头像）
    }

    /**
     * 画圆形头像：有头像画图，无头像回退「用户色底 + 首字母」。
     * 供 bubbleChatList 的气泡渲染复用，保证所有头像视觉一致。
     */
    public static void paintAvatar(Graphics2D g2, String username, int x, int y, int size) {
        paintAvatar(g2, username, x, y, size, Math.max(10, size * 13 / 30));
    }

    public static void paintAvatar(Graphics2D g2, String username, int x, int y, int size, int fontSize) {
        BufferedImage img = getAvatarImage(username);
        if (img != null) {
            // 圆形裁剪 + 细白描边，微信风格
            Shape oldClip = g2.getClip();
            g2.setClip(new Ellipse2D.Float(x, y, size, size));
            g2.drawImage(img, x, y, size, size, null);
            g2.setClip(oldClip);
            g2.setColor(Color.WHITE);
            g2.drawOval(x, y, size, size);
            return;
        }
        g2.setColor(getColorForUser(username));
        g2.fillOval(x, y, size, size);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Microsoft YaHei", Font.BOLD, fontSize));
        FontMetrics fm = g2.getFontMetrics();
        String initial = username.isEmpty() ? "?" : username.substring(0, 1).toUpperCase();
        g2.drawString(initial, x + size / 2 - fm.stringWidth(initial) / 2,
                y + size / 2 + (fm.getAscent() - fm.getDescent()) / 2);
    }

    // ===== 图片消息：编解码工具 =====

    /** content 是否为图片消息（[IMG] 前缀） */
    public static boolean isImageContent(String content) {
        return content != null && content.startsWith(IMG_PREFIX);
    }

    /**
     * 解码图片消息内容。非图片消息返回 null；Base64 非法 / 非图片 / 超像素上限
     * 一律返回 null（渲染端回退占位符），不抛异常。
     */
    public static BufferedImage decodeImageMessage(String content) {
        if (!isImageContent(content)) {
            return null;
        }
        try {
            byte[] data = Base64.getDecoder().decode(content.substring(IMG_PREFIX.length()));
            if (data.length > MAX_IMAGE_MESSAGE_BYTES) {
                return null;
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null) {
                return null;
            }
            // 防御超扁平大图（如 10000x10000 的 PNG 只有几十 KB，渲染缩放时才炸内存）
            if ((long) img.getWidth() * img.getHeight() > MAX_DECODED_PIXELS) {
                return null;
            }
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从文件构造图片消息内容（[IMG]+Base64）：限 1MB、长边压缩到 1280px。
     * 有透明通道用 PNG（保持透明），否则 JPEG 压缩体积。
     */
    public static String buildImageMessage(File file) throws IOException {
        byte[] raw = Files.readAllBytes(file.toPath());
        if (raw.length > MAX_IMAGE_MESSAGE_BYTES) {
            throw new IOException("图片超过 1MB 限制，请选择更小的图片");
        }
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null) {
            throw new IOException("无法识别的图片格式（仅支持 JPG / PNG）");
        }
        BufferedImage scaled = scaleToMaxEdge(src, IMAGE_MAX_EDGE);
        byte[] data = encodeImage(scaled, scaled.getColorModel().hasAlpha());
        return IMG_PREFIX + Base64.getEncoder().encodeToString(data);
    }

    /** 从文件构造头像字节：压缩到 128px 边长内，统一 PNG（支持透明） */
    public static byte[] buildAvatarBytes(File file) throws IOException {
        BufferedImage src = ImageIO.read(file);
        if (src == null) {
            throw new IOException("无法识别的图片格式（仅支持 JPG / PNG）");
        }
        BufferedImage scaled = scaleToMaxEdge(src, AVATAR_MAX_EDGE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", out);
        if (out.size() > MAX_AVATAR_BYTES) {
            throw new IOException("头像文件过大，请换一张更小的图片");
        }
        return out.toByteArray();
    }

    /**
     * 图片加载失败的占位图（[IMG] 消息解码失败时渲染它，避免把 Base64 垃圾当文本显示）。
     * 懒创建，线程安全（并发下重复创建无害，仅多一次分配）。
     */
    public static BufferedImage placeholderImage() {
        BufferedImage img = placeholderImage;
        if (img == null) {
            img = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(235, 238, 245));
            g.fillRect(0, 0, 160, 120);
            g.setColor(TEXT_GRAY);
            g.setFont(getChatFont(13));
            String text = "图片加载失败";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (160 - fm.stringWidth(text)) / 2, (120 + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
            placeholderImage = img;
        }
        return img;
    }

    /** 等比缩到最长边不超过 maxEdge（保持透明通道） */
    private static BufferedImage scaleToMaxEdge(BufferedImage src, int maxEdge) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxEdge && h <= maxEdge) {
            return src;
        }
        double scale = Math.min(maxEdge / (double) w, maxEdge / (double) h);
        int nw = Math.max(1, (int) (w * scale));
        int nh = Math.max(1, (int) (h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    /** 按是否有透明通道选格式编码（JPEG 压缩率高，PNG 保透明） */
    private static byte[] encodeImage(BufferedImage img, boolean alpha) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!alpha) {
            // JPEG 不吃 alpha，先转 RGB 再写
            BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
            ImageIO.write(rgb, "jpg", out);
        } else {
            ImageIO.write(img, "png", out);
        }
        return out.toByteArray();
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
