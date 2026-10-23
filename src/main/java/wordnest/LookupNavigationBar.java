package wordnest;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.FlowLayout;
import java.awt.Font;

/** Shared by the actual window and offscreen UI checks. */
final class LookupNavigationBar extends JPanel {
    private final JButton back = Theme.button("← 后退", false);
    private final JButton forward = Theme.button("前进 →", false);
    private final JButton recent = Theme.button("历史", false);

    LookupNavigationBar(Runnable onBack, Runnable onForward, Runnable onRecent) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 0));
        setOpaque(false);
        back.setName("history-back"); forward.setName("history-forward"); recent.setName("history-recent");
        back.setToolTipText("返回上一个词（Alt+←）");
        forward.setToolTipText("前进到下一个词（Alt+→）");
        recent.setToolTipText("最近查询的 100 个词，仅保存在本机");
        for (JButton button : new JButton[]{back, forward, recent}) {
            button.setFont(Theme.font(Font.PLAIN, 12));
            add(button);
        }
        back.addActionListener(e -> onBack.run());
        forward.addActionListener(e -> onForward.run());
        recent.addActionListener(e -> onRecent.run());
        update(false, false, 0);
    }

    void update(boolean canBack, boolean canForward, int recentCount) {
        back.setEnabled(canBack);
        forward.setEnabled(canForward);
        recent.setText(recentCount == 0 ? "历史" : "历史 · " + recentCount);
        recent.setEnabled(recentCount > 0);
    }
}
