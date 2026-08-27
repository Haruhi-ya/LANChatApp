package chatPackage;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信风格的气泡消息列表：单列 JTable + 自定义绘制。
 *
 * 架构选型（重要，不要轻易改回）：
 *  - JList 不支持可变行高——BasicListUI 只按第一个元素计算一个 cellHeight，全部行共用。
 *    JTable 靠 prepareRenderer 里按内容 setRowHeight(row, h) 实现可变行高，这是唯一
 *    现成可用的标准做法。
 *  - cell 里不放任何 JTextPane/JLabel 组件——Swing 的 renderer 是绘制印章，事件永远
 *    派发到外层表格，里面的组件收不到鼠标（右键菜单、文本选择全部失效），且每次渲染
 *    都重建组件代价高。文本用 TextLayout 直接绘制，复制通过右键菜单取 model 原文。
 *  - 换行用 LineBreakMeasurer + BreakIterator.getCharacterInstance()：按字符边界断行且
 *    不会拆开 surrogate pair。这也解决「连续长英文/URL/中文串撑出横向滚动条」的问题，
 *    默认的词边界断行做不到。
 *  - TextLayout 换行结果按「列宽」为 key 缓存在 item 里：renderer 实例会被 Swing 复用，
 *    缓存放 renderer 里会在不同行之间反复失效；放 item 里则每个宽度只排一次版。
 *
 * 线程约定：所有方法都在 EDT 上调用（由 clientChatUI / privateChatUI 保证）。
 */
public class bubbleChatList extends JPanel {

    /** 气泡最大宽度 = 列宽的该比例（短消息气泡不能横贯整列） */
    private static final float BUBBLE_MAX_WIDTH_RATIO = 0.62f;

    /** 图片消息的最大显示高度（像素），超高图片等比缩窄 */
    private static final int IMAGE_MAX_HEIGHT = 400;

    private static final Color BUBBLE_OTHER_BG = new Color(244, 246, 251);
    private static final Color BUBBLE_MINE_BG = chatTheme.PRIMARY;
    private static final Color BUBBLE_OTHER_TEXT = chatTheme.TEXT_DARK;
    private static final Color BUBBLE_MINE_TEXT = Color.WHITE;
    private static final Color META_TEXT = new Color(150, 155, 170);

    /** 右键菜单行为由宿主注入（判断可否撤回、执行撤回） */
    public interface MenuHandler {
        boolean canRecall(BubbleMsg msg);

        void onRecall(BubbleMsg msg);
    }

    /** 一条气泡消息（不可变数据，可被撤回原地替换） */
    public static class BubbleMsg {
        public final String sender;
        public final String msgId;   // 空字符串 = 老消息，不可撤回
        public final long timestamp;
        public final String content;
        public final boolean mine;
        public final boolean canRecall;
        public final String mentionName; // 内容里 @ 到的自己，用于高亮；null = 无
        public final Image image;        // 图片消息的解码图；null = 文本消息

        BubbleMsg(String sender, String msgId, long timestamp, String content,
                  boolean mine, boolean canRecall, String mentionName) {
            this(sender, msgId, timestamp, content, mine, canRecall, mentionName, null);
        }

        BubbleMsg(String sender, String msgId, long timestamp, String content,
                  boolean mine, boolean canRecall, String mentionName, Image image) {
            this.sender = sender;
            this.msgId = msgId;
            this.timestamp = timestamp;
            this.content = content;
            this.mine = mine;
            this.canRecall = canRecall;
            this.mentionName = mentionName;
            this.image = image;
        }
    }

    /** 系统提示 / 撤回提示条目 */
    public static class BubbleSystem {
        public final String text;

        BubbleSystem(String text) {
            this.text = text;
        }
    }

    /** 某宽度下排好版的文本行 / 图片尺寸（缓存在 item 里） */
    private static class Layout {
        final int width;
        final List<TextLayout> lines = new ArrayList<>();
        final int totalTextHeight; // 文本：行总高；图片：图片显示高度
        final int imageW;          // 图片显示宽度（文本消息为 0）
        final int imageH;          // 图片显示高度（文本消息为 0）

        Layout(int width, List<TextLayout> lines, int totalTextHeight) {
            this.width = width;
            this.lines.addAll(lines);
            this.totalTextHeight = totalTextHeight;
            this.imageW = 0;
            this.imageH = 0;
        }

        Layout(int width, int imageW, int imageH) {
            this.width = width;
            this.totalTextHeight = imageH;
            this.imageW = imageW;
            this.imageH = imageH;
        }
    }

    /** 表模型条目：气泡消息或系统条目 */
    private static class RowItem {
        final BubbleMsg msg;       // 非 null 时为气泡
        final BubbleSystem system; // 非 null 时为系统/撤回条目
        final boolean isRecall;    // 系统条目是否为撤回提示（灰色斜体）
        Layout layout;             // 按列宽缓存的排版

        RowItem(BubbleMsg msg) {
            this.msg = msg;
            this.system = null;
            this.isRecall = false;
        }

        RowItem(BubbleSystem system, boolean isRecall) {
            this.msg = null;
            this.system = system;
            this.isRecall = isRecall;
        }
    }

    private final JTable table;
    private final MessageModel model;
    private final MenuHandler menuHandler;
    private JScrollPane scrollPane;
    private boolean stickyBottom = true; // 是否跟随新消息自动滚底
    private boolean internalScroll;      // 程序性滚动标志：不把 setValue 当作用户行为
    private int lastBarValue;            // 上一拍滚动条值：值变小 = 用户上滚

    public bubbleChatList(MenuHandler menuHandler) {
        this.menuHandler = menuHandler;
        model = new MessageModel();
        table = new JTable(model) {
            @Override
            public Component prepareRenderer(javax.swing.table.TableCellRenderer renderer,
                                             int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                int h = c.getPreferredSize().height;
                if (getRowHeight(row) != h) {
                    setRowHeight(row, h);
                }
                return c;
            }
        };
        configureTable();

        setLayout(new BorderLayout());
        scrollPane = chatTheme.wrapScroll(table);
        add(scrollPane, BorderLayout.CENTER);

        // 跟踪滚动位置：在底部才跟随新消息自动滚底，上翻读历史时不打断。
        //
        // 判定规则：
        //  - 到底了 → 粘底（无论什么原因）
        //  - 值变小 → 用户上滚，脱离粘底
        //  - 其他（新消息让 maximum 变大、布局期的程序性调整等）→ 保持原状态。
        //    若简单按「当前是否在底部」整体重算，内容增长的那一瞬值还没来得及跟上，
        //    就会被误判成用户滚离了底部，从此不再自动滚。
        scrollPane.getVerticalScrollBar().addAdjustmentListener(new AdjustmentListener() {
            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                if (internalScroll) {
                    return; // 自己的 setValue，不当作用户行为
                }
                JScrollBar bar = scrollPane.getVerticalScrollBar();
                int value = bar.getValue();
                boolean atBottom = value + bar.getVisibleAmount() >= bar.getMaximum() - 8;
                if (atBottom) {
                    stickyBottom = true;
                } else if (value < lastBarValue) {
                    stickyBottom = false;
                }
                lastBarValue = value;
            }
        });
    }

    private void configureTable() {
        table.setShowGrid(false);
        table.setTableHeader(null);
        table.setRowSelectionAllowed(false);
        table.setCellSelectionEnabled(false);
        table.setColumnSelectionAllowed(false);
        table.setFocusable(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBackground(chatTheme.CARD_BG);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.getColumnModel().getColumn(0).setCellRenderer(new BubbleRenderer());

        // 右键菜单必须挂在 JTable 上，cell 内的组件收不到鼠标事件
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { showRowPopup(e); }
            @Override
            public void mouseReleased(MouseEvent e) { showRowPopup(e); }
        });
    }

    private void showRowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0 || row >= model.items.size()) {
            return;
        }
        RowItem item = model.items.get(row);
        if (item.msg == null) {
            return; // 系统/撤回条目没有菜单
        }

        JPopupMenu menu = new JPopupMenu();
        if (item.msg.canRecall && menuHandler != null) {
            JMenuItem recallItem = new JMenuItem("撤回");
            recallItem.addActionListener(ev -> menuHandler.onRecall(item.msg));
            menu.add(recallItem);
        }
        if (item.msg.image != null) {
            JMenuItem saveItem = new JMenuItem("保存图片");
            saveItem.addActionListener(ev -> saveImage(item.msg));
            menu.add(saveItem);
        } else {
            JMenuItem copyItem = new JMenuItem("复制这条消息");
            copyItem.addActionListener(ev ->
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(item.msg.content), null));
            menu.add(copyItem);
        }
        menu.show(table, e.getX(), e.getY());
    }

    /** 图片消息另存为 PNG */
    private void saveImage(BubbleMsg msg) {
        if (!(msg.image instanceof BufferedImage)) {
            JOptionPane.showMessageDialog(table, "图片数据不可用", "保存图片",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(msg.sender + "_" + msg.timestamp + ".png"));
        if (chooser.showSaveDialog(table) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".png")) {
            file = new File(file.getParentFile(), file.getName() + ".png");
        }
        try {
            ImageIO.write((BufferedImage) msg.image, "png", file);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(table, "保存失败：" + e.getMessage(), "保存图片",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // ===== 对外操作（EDT） =====

    /** 批量追加历史消息（一次 fire，避免逐条刷新卡顿） */
    public void addHistory(List<BubbleMsg> msgs) {
        if (msgs.isEmpty()) {
            return;
        }
        int start = model.items.size();
        for (BubbleMsg m : msgs) {
            model.items.add(new RowItem(m));
        }
        model.fireTableRowsInserted(start, model.items.size() - 1);
        afterModelChange();
    }

    /** 追加一条气泡消息 */
    public void addMessage(BubbleMsg msg) {
        model.items.add(new RowItem(msg));
        model.fireTableRowsInserted(model.items.size() - 1, model.items.size() - 1);
        afterModelChange();
    }

    /** 追加一条系统/撤回提示 */
    public void addSystem(String text, boolean isRecall) {
        model.items.add(new RowItem(new BubbleSystem(text), isRecall));
        model.fireTableRowsInserted(model.items.size() - 1, model.items.size() - 1);
        afterModelChange();
    }

    /** 撤回：按 msgId 找到气泡，原地替换为撤回提示；找不到返回 false */
    public boolean recallMessage(String msgId, String recallText) {
        for (int i = 0; i < model.items.size(); i++) {
            RowItem item = model.items.get(i);
            if (item.msg != null && item.msg.msgId.equals(msgId)) {
                model.items.set(i, new RowItem(new BubbleSystem(recallText), true));
                model.fireTableRowsUpdated(i, i);
                afterModelChange();
                return true;
            }
        }
        return false;
    }

    /** 清空全部内容 */
    public void clear() {
        model.items.clear();
        model.fireTableDataChanged();
        afterModelChange();
    }

    /**
     * 强制整表重绘（头像按需加载完成后调用）。头像不影响行高，
     * 只需要 repaint 不需要 revalidate。
     */
    public void repaintTable() {
        table.repaint();
    }

    /** 气泡消息里是否已有该 msgId（撤回事件可能早于历史回放到达） */
    public boolean containsMsgId(String msgId) {
        for (RowItem item : model.items) {
            if (item.msg != null && item.msg.msgId.equals(msgId)) {
                return true;
            }
        }
        return false;
    }

    private void afterModelChange() {
        // 行高按内容计算，数据变化后让 prepareRenderer 重新跑一遍
        for (int i = 0; i < model.items.size(); i++) {
            table.prepareRenderer(table.getCellRenderer(i, 0), i, 0);
        }
        scrollToBottomIfSticky();
    }

    /**
     * 粘底滚动。setValue(maximum) 比 scrollRectToVisible 稳：超高行只保证行顶可见。
     *
     * 必须分两步滚：setRowHeight 之后 JTable 的尺寸和滚动条的 maximum 要等下一次
     * 布局（validate）才更新，此刻立即读到的还是旧值，setValue 会被钳制在旧上限。
     * 所以先立即滚一次，再 invokeLater 延迟一拍滚第二次，确保真的贴到底。
     */
    private void scrollToBottomIfSticky() {
        if (!stickyBottom || scrollPane == null) {
            return;
        }
        JScrollBar bar = scrollPane.getVerticalScrollBar();
        internalScroll = true;
        bar.setValue(bar.getMaximum());
        internalScroll = false;
        SwingUtilities.invokeLater(() -> {
            if (!stickyBottom || scrollPane == null) {
                return;
            }
            internalScroll = true;
            bar.setValue(bar.getMaximum());
            internalScroll = false;
        });
    }

    // ===== 模型 =====

    private static class MessageModel extends AbstractTableModel {
        final List<RowItem> items = new ArrayList<>();

        @Override
        public int getRowCount() {
            return items.size();
        }

        @Override
        public int getColumnCount() {
            return 1;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return items.get(rowIndex);
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }

    // ===== 渲染 =====

    /** 把一段文本排成指定宽度内的多行（按字符断行，不拆 surrogate pair） */
    private static Layout layoutText(String text, int width, Font font, AttributedString as) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        LineBreakMeasurer measurer = new LineBreakMeasurer(as.getIterator(),
                BreakIterator.getCharacterInstance(), frc);
        List<TextLayout> lines = new ArrayList<>();
        int totalHeight = 0;
        while (measurer.getPosition() < text.length()) {
            TextLayout layout = measurer.nextLayout(Math.max(1, width));
            lines.add(layout);
            totalHeight += (int) Math.ceil(layout.getAscent() + layout.getDescent() + layout.getLeading());
        }
        if (lines.isEmpty()) {
            lines.add(new TextLayout(" ", font.getAttributes(), frc));
            totalHeight = (int) Math.ceil(lines.get(0).getAscent() + lines.get(0).getDescent());
        }
        return new Layout(width, lines, totalHeight);
    }

    /** 图片显示尺寸：等比缩放，宽不超 availW、高不超 IMAGE_MAX_HEIGHT；坏图给占位尺寸 */
    private static Layout layoutImage(Image image, int columnWidth, int availW) {
        int iw = image.getWidth(null);
        int ih = image.getHeight(null);
        if (iw <= 0 || ih <= 0) {
            return new Layout(columnWidth, Math.min(availW, 160), 120);
        }
        double scale = Math.min(1.0, Math.min(availW / (double) iw, IMAGE_MAX_HEIGHT / (double) ih));
        return new Layout(columnWidth, Math.max(1, (int) (iw * scale)), Math.max(1, (int) (ih * scale)));
    }

    private class BubbleRenderer extends JComponent implements javax.swing.table.TableCellRenderer {
        private RowItem item;
        private int columnWidth;

        BubbleRenderer() {
            setOpaque(true);
            setFont(chatTheme.getChatFont(14));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            item = (RowItem) value;
            columnWidth = table.getColumnModel().getColumn(0).getWidth();
            if (item.layout == null || item.layout.width != columnWidth) {
                item.layout = layoutFor(item, columnWidth);
            }
            return this;
        }

        /** 目标换行宽度：气泡文本区或系统文本区（居中留白 40） */
        private Layout layoutFor(RowItem rowItem, int width) {
            if (rowItem.msg != null) {
                if (rowItem.msg.image != null) {
                    // 图片消息：按列宽约束显示尺寸（最大宽 = 气泡上限，最大高 400px）
                    int bubbleMax = Math.max(40, (int) (width * BUBBLE_MAX_WIDTH_RATIO));
                    return layoutImage(rowItem.msg.image, width, bubbleMax - 24);
                }
                int bubbleMax = Math.max(40, (int) (width * BUBBLE_MAX_WIDTH_RATIO));
                int textWidth = bubbleMax - 24; // 气泡水平内边距 12*2
                Color normal = rowItem.msg.mine ? BUBBLE_MINE_TEXT : BUBBLE_OTHER_TEXT;
                AttributedString as = new AttributedString(rowItem.msg.content);
                as.addAttribute(TextAttribute.FONT, getFont());
                as.addAttribute(TextAttribute.FOREGROUND, normal);
                if (rowItem.msg.mentionName != null && !rowItem.msg.mentionName.isEmpty()) {
                    // @自己 的片段高亮：对方气泡里用主题蓝；自己的气泡是蓝底，
                    // 改用亮黄保证可读
                    Color highlight = rowItem.msg.mine ? new Color(255, 235, 130) : chatTheme.PRIMARY;
                    String needle = "@" + rowItem.msg.mentionName;
                    int from = 0;
                    while ((from = rowItem.msg.content.indexOf(needle, from)) >= 0) {
                        int end = from + needle.length();
                        as.addAttribute(TextAttribute.FOREGROUND, highlight, from, end);
                        as.addAttribute(TextAttribute.WEIGHT, TextAttribute.WEIGHT_BOLD, from, end);
                        from = end;
                    }
                }
                // 微软雅黑缺 emoji 字形（渲染成方框），emoji 段换成 emoji 字体
                chatTheme.applyEmojiFontRanges(as, rowItem.msg.content, getFont().getSize());
                return layoutText(rowItem.msg.content, textWidth, getFont(), as);
            } else {
                AttributedString as = new AttributedString(rowItem.system.text);
                as.addAttribute(TextAttribute.FONT, getFont());
                chatTheme.applyEmojiFontRanges(as, rowItem.system.text, getFont().getSize());
                return layoutText(rowItem.system.text, Math.max(40, width - 40), getFont(), as);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            if (item == null || item.layout == null) {
                return new Dimension(columnWidth, 10);
            }
            if (item.msg != null) {
                int bubbleH = item.layout.totalTextHeight + 8; // 文本上下内边距 4*2
                // 双方消息都在气泡上方显示昵称，多出 18px 昵称行；
                // 自己的消息头像在右侧，单行气泡（约 26px）比头像（30px）矮，行高按头像算
                int contentH = 18 + Math.max(30, bubbleH);
                int metaH = 16; // 气泡右下角时间行
                return new Dimension(columnWidth, 4 + contentH + metaH + 8);
            } else {
                return new Dimension(columnWidth, item.layout.totalTextHeight + 12);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (item == null || item.layout == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            if (item.msg != null) {
                paintBubble(g2);
            } else {
                paintSystem(g2);
            }
            g2.dispose();
        }

        private void paintBubble(Graphics2D g2) {
            BubbleMsg m = item.msg;
            int maxBubbleWidth = Math.max(40, (int) (columnWidth * BUBBLE_MAX_WIDTH_RATIO));
            Layout layout = item.layout;

            // 气泡宽度：文本按最宽一行，图片按布局好的显示宽度（都包住内容，微信风格）
            int bubbleW;
            if (m.image != null) {
                bubbleW = Math.min(maxBubbleWidth, layout.imageW + 24);
            } else {
                int textW = 0;
                for (TextLayout line : layout.lines) {
                    textW = Math.max(textW, (int) Math.ceil(line.getAdvance()));
                }
                bubbleW = Math.min(maxBubbleWidth, textW + 24);
            }
            int bubbleH = layout.totalTextHeight + 8;

            // 自己的消息：头像在右侧，气泡在头像左侧；对方消息：头像在左侧，气泡在右侧
            int avatarX;
            int bubbleX;
            int y;
            if (m.mine) {
                avatarX = columnWidth - 10 - 30;
                bubbleX = avatarX - 8 - bubbleW;
            } else {
                avatarX = 10;
                bubbleX = 46; // 头像 + 间距
            }
            y = 4 + 18; // 气泡在昵称行下方开始（双方一致）

            // 头像（自己右侧 / 对方左侧）：有自定义头像画图，否则颜色+首字母
            chatTheme.paintAvatar(g2, m.sender, avatarX, y, 30);

            // 气泡上方显示昵称：对方靠左，自己靠右对齐，与气泡两侧对称
            g2.setColor(META_TEXT);
            g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            if (m.mine) {
                g2.drawString(m.sender, bubbleX + bubbleW - fm.stringWidth(m.sender), y - 4);
            } else {
                g2.drawString(m.sender, bubbleX, y - 4);
            }

            // 气泡底
            g2.setColor(m.mine ? BUBBLE_MINE_BG : BUBBLE_OTHER_BG);
            g2.fill(new RoundRectangle2D.Float(bubbleX, y, bubbleW, bubbleH, 12, 12));

            if (m.image != null) {
                // 图片：圆角裁切绘制（内边距 4），与气泡圆角一致
                Shape oldClip = g2.getClip();
                g2.setClip(new RoundRectangle2D.Float(bubbleX + 4, y + 4,
                        layout.imageW, layout.imageH, 8, 8));
                g2.drawImage(m.image, bubbleX + 4, y + 4, layout.imageW, layout.imageH, null);
                g2.setClip(oldClip);
            } else {
                // 文本
                float textY = y + 4;
                float textX = bubbleX + 12;
                g2.setFont(getFont());
                for (TextLayout line : layout.lines) {
                    line.draw(g2, textX, textY + line.getAscent());
                    textY += line.getAscent() + line.getDescent() + line.getLeading();
                }
            }

            // 时间
            g2.setColor(m.mine ? new Color(255, 255, 255, 160) : META_TEXT);
            g2.setFont(new Font("Dialog", Font.PLAIN, 10));
            String time = chatTheme.formatTime(m.timestamp);
            fm = g2.getFontMetrics();
            g2.drawString(time, bubbleX + bubbleW - fm.stringWidth(time) - 8, y + bubbleH + 12);
        }

        private void paintSystem(Graphics2D g2) {
            Layout layout = item.layout;
            g2.setFont(new Font("Microsoft YaHei", item.isRecall ? Font.ITALIC : Font.PLAIN, 12));
            g2.setColor(META_TEXT);
            float y = 6;
            for (TextLayout line : layout.lines) {
                float x = (columnWidth - line.getAdvance()) / 2;
                line.draw(g2, x, y + line.getAscent());
                y += line.getAscent() + line.getDescent() + line.getLeading();
            }
        }
    }

    // ===== 工厂：供外部构造气泡消息 =====

    public static BubbleMsg bubble(String sender, String msgId, long timestamp, String content,
                                   boolean mine, boolean canRecall) {
        return new BubbleMsg(sender, msgId, timestamp, content, mine, canRecall, null);
    }

    public static BubbleMsg bubble(String sender, String msgId, long timestamp, String content,
                                   boolean mine, boolean canRecall, String mentionName) {
        return new BubbleMsg(sender, msgId, timestamp, content, mine, canRecall, mentionName);
    }

    /** 图片消息气泡（image 已解码，content 仍存 [IMG]Base64 原文） */
    public static BubbleMsg bubbleImage(String sender, String msgId, long timestamp, String content,
                                        boolean mine, boolean canRecall, Image image) {
        return new BubbleMsg(sender, msgId, timestamp, content, mine, canRecall, null, image);
    }

    public static String recallText(String byWho, boolean mine) {
        return (mine ? "你" : byWho) + "撤回了一条消息";
    }
}
