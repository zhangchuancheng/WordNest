package wordnest;

import javax.swing.*;
import java.awt.*;

/** Scrollable preferences with an independently sized, always visible action row. */
final class PreferencesPanel extends JPanel {
    final JComboBox<String> close = new JComboBox<>(new String[]{"每次询问", "最小化到系统托盘", "退出程序"});
    final JComboBox<String> minimize = new JComboBox<>(new String[]{"最小化到任务栏", "最小化到系统托盘"});
    final JButton save = new JButton("保存"), cancel = new JButton("取消"), about = new JButton("关于与说明");
    final JScrollPane scroll;

    private static final class ScrollBody extends JPanel implements Scrollable {
        ScrollBody() { super(new BorderLayout()); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 18; }
        public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return Math.max(18, r.height - 18); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    PreferencesPanel(int closeIndex, int minimizeIndex) {
        super(new BorderLayout(0, 14));
        setBackground(Theme.PAPER);
        Theme.pad(this, 16, 16, 16, 16);
        close.setSelectedIndex(closeIndex); minimize.setSelectedIndex(minimizeIndex);
        close.getAccessibleContext().setAccessibleName("关闭窗口时");
        minimize.getAccessibleContext().setAccessibleName("最小化窗口时");
        JPanel form = Theme.panel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(Theme.label("点击关闭按钮 × 时", 13, Theme.INK), c);
        c.gridy++; form.add(close, c);
        c.gridy++; c.insets = new Insets(12, 0, 8, 0);
        form.add(Theme.label("点击最小化按钮 − 时", 13, Theme.INK), c);
        c.gridy++; c.insets = new Insets(0, 0, 12, 0); form.add(minimize, c);
        JTextArea explanation = new JTextArea("复制查词支持英文单词和短语（最多 8 词 / 120 字符）。\n后台运行时仍按开关状态工作，不会自动弹出窗口。\n可从托盘菜单或再次打开 EXE 恢复；右键托盘图标可彻底退出。");
        explanation.setEditable(false); explanation.setOpaque(false);
        explanation.setLineWrap(true); explanation.setWrapStyleWord(true);
        explanation.setFont(Theme.font(Font.PLAIN, 12)); explanation.setForeground(Theme.MUTED);
        c.gridy++; form.add(explanation, c);
        // Width-tracking viewport lets wrapped text grow vertically instead of clipping.
        JPanel body = new ScrollBody();
        body.setOpaque(false); body.add(form, BorderLayout.NORTH);
        scroll = new JScrollPane(body);
        scroll.setBorder(null); scroll.getViewport().setOpaque(false); scroll.setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        scroll.setPreferredSize(new Dimension(420, 300));
        add(scroll, BorderLayout.CENTER);
        JPanel actions = Theme.panel(new GridLayout(1, 3, 8, 0));
        for (JButton button : new JButton[]{save, cancel, about}) {
            button.setFont(Theme.font(Font.PLAIN, 13)); actions.add(button);
        }
        add(actions, BorderLayout.SOUTH);
    }
}
