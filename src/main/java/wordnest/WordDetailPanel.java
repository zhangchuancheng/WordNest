package wordnest;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/** Dictionary reading surface. Source definitions stay intact; English starts collapsed. */
final class WordDetailPanel extends JPanel {
    private static final Pattern POS = Pattern.compile(
            "^((?:(?:adj|adv|vt|vi|v|n|a|ad|pron|prep|conj|interj|int|num|art|aux|det|abbr|pl)\\.)(?:\\s*(?:&|/|,)\\s*(?:adj|adv|vt|vi|v|n|a|ad|pron|prep|conj|interj|int|num|art|aux|det|abbr|pl)\\.)*)\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> POS_NAMES = Map.ofEntries(
            Map.entry("n.", "名词"), Map.entry("v.", "动词"), Map.entry("vt.", "及物动词"),
            Map.entry("vi.", "不及物动词"), Map.entry("a.", "形容词"), Map.entry("adj.", "形容词"),
            Map.entry("ad.", "副词"), Map.entry("adv.", "副词"), Map.entry("pron.", "代词"),
            Map.entry("prep.", "介词"), Map.entry("conj.", "连词"), Map.entry("interj.", "感叹词"),
            Map.entry("int.", "感叹词"), Map.entry("num.", "数词"), Map.entry("art.", "冠词"),
            Map.entry("aux.", "助动词"), Map.entry("det.", "限定词"), Map.entry("abbr.", "缩写"),
            Map.entry("pl.", "复数"));
    private static final Map<String, String> TAGS = Map.of("zk", "中考", "gk", "高考",
            "cet4", "四级", "cet6", "六级", "ky", "考研", "ielts", "雅思", "toefl", "托福", "gre", "GRE");

    WordDetailPanel(WordEntry entry, JButton favorite, JButton lemmaButton) {
        this(entry, favorite, lemmaButton, null);
    }

    WordDetailPanel(WordEntry entry, JButton favorite, JButton lemmaButton, Consumer<String> speak) {
        super(new BorderLayout()); setOpaque(false); setAlignmentX(LEFT_ALIGNMENT);
        Theme.Card card = new Theme.Card(null);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS)); Theme.pad(card, 20, 18, 18, 18);
        add(card, BorderLayout.CENTER);

        JPanel wordRow = verticalBounded(new BorderLayout(12, 0));
        ReadingText word = new ReadingText(entry.word(), Theme.latin(Font.BOLD, 30), Theme.INK);
        word.setName("headword"); word.setToolTipText(entry.word());
        wordRow.add(word, BorderLayout.CENTER);
        JPanel favoriteWrap = Theme.panel(new BorderLayout()); favoriteWrap.add(favorite, BorderLayout.NORTH);
        favorite.setFont(Theme.font(Font.PLAIN, 12));
        wordRow.add(favoriteWrap, BorderLayout.EAST); card.add(wordRow); gap(card, 6);
        ReadingText phonetic = new ReadingText(entry.phonetic().isBlank() ? "该词条暂无音标" : "/ " + entry.phonetic() + " /",
                entry.phonetic().isBlank() ? Theme.font(Font.PLAIN, 12) : Theme.latin(Font.PLAIN, 15), Theme.MUTED);
        JPanel pronunciation = textRow(phonetic);
        if (speak != null) {
            JButton play = Theme.button("朗读单词", false);
            play.setName("speak-word"); play.setFont(Theme.font(Font.PLAIN, 11));
            play.getAccessibleContext().setAccessibleName("朗读单词 " + entry.word());
            play.addActionListener(event -> speak.accept(entry.word()));
            pronunciation.add(play, BorderLayout.EAST);
        }
        card.add(pronunciation);
        TagRow tags = new TagRow();
        for (String tag : entry.tags().split("\\s+")) if (TAGS.containsKey(tag)) {
            JLabel chip = Theme.label(TAGS.get(tag), 10, Theme.GREEN);
            chip.setOpaque(true); chip.setBackground(Theme.PALE); Theme.pad(chip, 3, 8, 3, 8); tags.add(chip);
        }
        if (tags.getComponentCount() > 0) { gap(card, 10); card.add(tags); }
        gap(card, 20);
        JLabel heading = Theme.label("中文释义", 14, Theme.INK); heading.setFont(Theme.font(Font.BOLD, 14));
        heading.setAlignmentX(LEFT_ALIGNMENT); card.add(heading); gap(card, 10);
        List<Meaning> meanings = meanings(entry.translation());
        if (meanings.isEmpty()) meanings = List.of(new Meaning("", "该词条暂无中文释义。"));
        for (int index = 0; index < meanings.size(); index++) {
            Meaning item = meanings.get(index);
            card.add(meaningRow(item, index == 0));
            if (index < meanings.size() - 1) gap(card, 5);
        }
        gap(card, 14);
        JPanel english = verticalBounded(new BorderLayout(0, 10));
        english.setBackground(new Color(0xF6F7F3)); english.setOpaque(true); Theme.pad(english, 2, 8, 2, 8);
        JButton toggle = Theme.button("英文释义    ＋", false);
        toggle.setName("english-toggle"); toggle.setHorizontalAlignment(SwingConstants.LEFT);
        toggle.setToolTipText("展开或收起原词库的英文释义");
        toggle.getAccessibleContext().setAccessibleDescription("英文释义已收起；按空格或 Enter 展开");
        JPanel englishContent = textRow(new ReadingText(entry.definition().isBlank() ? "该词条暂无英文释义。" : entry.definition(),
                Theme.font(Font.PLAIN, 13), Theme.MUTED));
        englishContent.setName("english-content"); Theme.pad(englishContent, 0, 5, 12, 5); englishContent.setVisible(false);
        toggle.addActionListener(event -> {
            boolean expanded = !englishContent.isVisible(); englishContent.setVisible(expanded);
            toggle.setText(expanded ? "英文释义    −" : "英文释义    ＋");
            toggle.getAccessibleContext().setAccessibleDescription(expanded ? "英文释义已展开；再次激活收起" : "英文释义已收起；激活展开");
            english.revalidate(); revalidate(); repaint();
        });
        english.add(toggle, BorderLayout.NORTH); english.add(englishContent, BorderLayout.CENTER); card.add(english);
        if (java.util.Arrays.asList(entry.tags().split("\\s+")).contains("wn-expanded")) {
            gap(card, 8);
            JLabel source = Theme.label("来源：ECDICT 扩充词库", 11, Theme.MUTED);
            source.setName("expanded-dictionary-source"); source.setAlignmentX(LEFT_ALIGNMENT);
            source.setToolTipText("历史社区资料，部分为网络释义；原文按来源保留，可能有疏漏。");
            card.add(source);
        }
        if (lemmaButton != null) { gap(card, 12); lemmaButton.setAlignmentX(LEFT_ALIGNMENT); card.add(lemmaButton); }
    }

    private static JPanel meaningRow(Meaning meaning, boolean first) {
        JPanel row = verticalBounded(new BorderLayout(12, 0));
        row.setOpaque(true); row.setBackground(first ? Theme.PALE : Theme.WHITE);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, first ? 3 : 0, first ? 0 : 1, 0, first ? Theme.GREEN : Theme.LINE),
                new EmptyBorder(13, first ? 9 : 12, 13, 12)));
        if (!meaning.pos().isEmpty()) {
            JPanel badge = Theme.panel(null); badge.setLayout(new BoxLayout(badge, BoxLayout.Y_AXIS));
            JLabel abbreviation = Theme.label(displayPos(meaning.pos()), 11, Theme.GREEN);
            abbreviation.setFont(Theme.latin(Font.BOLD, 11)); badge.add(abbreviation);
            String label = POS_NAMES.getOrDefault(meaning.pos().toLowerCase(java.util.Locale.ROOT), "词性");
            badge.add(Theme.label(label, 10, Theme.GREEN));
            JPanel left = Theme.panel(new BorderLayout()); left.add(badge, BorderLayout.NORTH);
            left.setPreferredSize(new Dimension(Math.max(63, abbreviation.getPreferredSize().width), 36));
            row.add(left, BorderLayout.WEST);
        }
        ReadingText definition = new ReadingText(meaning.text(), Theme.font(Font.BOLD, 20), Theme.INK);
        definition.setName("meaning-text"); row.add(definition, BorderLayout.CENTER); return row;
    }

    /** Only recognized line-leading POS labels are removed from the displayed source text. */
    static List<Meaning> meanings(String translation) {
        List<Meaning> result = new ArrayList<>();
        for (String line : translation.split("\\R")) {
            if (line.isBlank()) continue;
            Matcher matcher = POS.matcher(line.strip());
            if (matcher.matches()) result.add(new Meaning(matcher.group(1), matcher.group(2)));
            else if (!result.isEmpty()) {
                Meaning prior = result.remove(result.size() - 1);
                result.add(new Meaning(prior.pos(), prior.text() + "\n" + line.strip()));
            } else result.add(new Meaning("", line.strip()));
        }
        return List.copyOf(result);
    }
    record Meaning(String pos, String text) { }

    static String displayPos(String source) {
        return source.replaceAll("(?i)(?<![A-Za-z])a\\.", "adj.")
                .replaceAll("(?i)(?<![A-Za-z])ad\\.", "adv.");
    }

    private static JPanel textRow(ReadingText area) {
        JPanel row = verticalBounded(new BorderLayout()); row.add(area, BorderLayout.CENTER); return row;
    }
    private static JPanel verticalBounded(LayoutManager layout) {
        JPanel panel = new JPanel(layout) {
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        panel.setOpaque(false); panel.setAlignmentX(LEFT_ALIGNMENT); return panel;
    }
    private static void gap(JPanel panel, int height) { panel.add(Box.createVerticalStrut(height)); }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

    /** Measure wrapped text against the space remaining in its actual BorderLayout row. */
    private static final class ReadingText extends JTextArea {
        ReadingText(String value, Font font, Color color) {
            super(value); setFont(font); setForeground(color); setOpaque(false); setEditable(false);
            setLineWrap(true); setWrapStyleWord(true); setBorder(null); setMargin(new Insets(0, 0, 0, 0));
        }
        @Override public Dimension getPreferredSize() {
            Container parent = getParent(); int width = 300;
            if (parent != null && parent.getWidth() > 0) {
                Insets insets = parent.getInsets(); width = parent.getWidth() - insets.left - insets.right;
                if (parent.getLayout() instanceof BorderLayout layout) {
                    for (String side : new String[]{BorderLayout.WEST, BorderLayout.EAST}) {
                        Component sibling = layout.getLayoutComponent(side);
                        if (sibling != null && sibling.isVisible()) width -= sibling.getPreferredSize().width + layout.getHgap();
                    }
                }
            }
            setSize(Math.max(40, width), Short.MAX_VALUE);
            Dimension preferred = super.getPreferredSize(); preferred.width = 1; return preferred;
        }
        @Override public Dimension getMinimumSize() { return new Dimension(1, getPreferredSize().height); }
    }

    private static final class TagRow extends JPanel {
        TagRow() { super(new FlowLayout(FlowLayout.LEFT, 5, 3)); setOpaque(false); setAlignmentX(LEFT_ALIGNMENT); }
        @Override public Dimension getPreferredSize() {
            int available = getWidth() > 0 ? getWidth() : 320;
            int used = 5, height = 0, lineHeight = 0;
            for (Component child : getComponents()) {
                Dimension d = child.getPreferredSize();
                if (used + d.width + 5 > available && used > 5) { height += lineHeight + 3; used = 5; lineHeight = 0; }
                used += d.width + 5; lineHeight = Math.max(lineHeight, d.height);
            }
            return new Dimension(1, height + lineHeight + 6);
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }
}
