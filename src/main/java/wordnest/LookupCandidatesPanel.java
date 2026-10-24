package wordnest;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Non-modal choices preserve the currently displayed entry and its pending personal note. */
final class LookupCandidatesPanel extends JPanel {
    private final JComboBox<String> choices = new JComboBox<>();
    private final JButton open = Theme.button("查看", true);
    private final JLabel title = Theme.label("有多个可能原形，请选择。", 11, Theme.MUTED);

    LookupCandidatesPanel(Consumer<String> lookup) {
        super(new BorderLayout(6, 4)); setOpaque(false); setName("lookup-candidates");
        add(title, BorderLayout.NORTH);
        choices.setName("lookup-candidate-options"); choices.setMinimumSize(new Dimension(20, 28));
        choices.setPrototypeDisplayValue("take into account"); add(choices, BorderLayout.CENTER);
        open.setName("lookup-candidate-open"); open.setEnabled(false);
        open.addActionListener(event -> { if (choices.getSelectedIndex() > 0) lookup.accept((String) choices.getSelectedItem()); });
        choices.addActionListener(event -> open.setEnabled(choices.getSelectedIndex() > 0));
        JButton dismiss = Theme.button("收起", false); dismiss.setName("lookup-candidate-dismiss"); dismiss.addActionListener(event -> clear());
        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.LEFT, 4, 0)); actions.add(open); actions.add(dismiss); add(actions, BorderLayout.EAST);
        setVisible(false);
    }

    void showCandidates(LookupResolver.Result result) {
        choices.removeAllItems(); choices.addItem("请选择原形…");
        for (WordEntry candidate : result.candidates()) choices.addItem(candidate.word());
        choices.setSelectedIndex(0);
        title.setText(result.truncated() ? "词形组合较多，请手动选择或缩短查询。" : "有多个可能原形，请手动选择。");
        setToolTipText("原查询：" + result.original());
        setVisible(true); revalidate();
    }

    void clear() { choices.removeAllItems(); setVisible(false); revalidate(); }
}
