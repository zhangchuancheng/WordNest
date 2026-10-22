package wordnest;

import javax.swing.*;
import java.awt.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import wordnest.LearningRepository.*;

/** Four learning views; switching views never replaces the personal-note editor. */
final class LearningPanel extends JPanel {
    private final String word;
    private final LearningRepository data;
    private final DictionaryRepository dictionary;
    private final Consumer<String> navigate;
    private final JPanel content = Theme.panel(new BorderLayout());
    private final Set<String> expanded = new HashSet<>();
    private String selected = "例句";
    private boolean showSources;
    private boolean showOpenSources;
    private JPanel body;

    LearningPanel(String word, LearningRepository data, DictionaryRepository dictionary, Consumer<String> navigate) {
        super(new BorderLayout(0, 14));
        this.word = word; this.data = data; this.dictionary = dictionary; this.navigate = navigate;
        setOpaque(false);
        JPanel tabs = Theme.panel(new GridLayout(1, 4, 5, 0));
        ButtonGroup group = new ButtonGroup();
        for (String name : List.of("例句", "派生", "词根", "近义词")) {
            JToggleButton button = new JToggleButton(name) {
                @Override protected void paintComponent(Graphics graphics) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    if (getModel().isRollover()) { g.setColor(Theme.PALE); g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8); }
                    g.setColor(Theme.LINE); g.fillRect(0, getHeight() - 1, getWidth(), 1);
                    if (isSelected()) { g.setColor(Theme.GREEN); g.fillRoundRect(4, getHeight() - 3, getWidth() - 8, 3, 3, 3); }
                    if (hasFocus()) { g.setColor(Theme.GREEN); g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 5, 7, 7); }
                    g.dispose(); super.paintComponent(graphics);
                }
            };
            button.setContentAreaFilled(false); button.setBorderPainted(false); button.setFocusPainted(false);
            button.setForeground(Theme.GREEN); button.setFont(Theme.font(Font.PLAIN, 14));
            button.setBorder(new javax.swing.border.EmptyBorder(8, 3, 10, 3));
            button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            button.setSelected(name.equals(selected)); group.add(button); tabs.add(button);
            button.addActionListener(e -> { selected = name; render(); });
        }
        add(tabs, BorderLayout.NORTH); add(content, BorderLayout.CENTER); render();
    }

    private List<CuratedItem> cards(String kind) {
        return data.curated(word).stream().filter(item -> item.kind().equals(kind)).toList();
    }
    private List<SupplementItem> supplements(String kind) {
        return data.supplemental(word).stream().filter(item -> item.kind().equals(kind)).toList();
    }
    private void render() {
        body = Theme.panel(null); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        switch (selected) {
            case "例句" -> examples();
            case "派生" -> derivations();
            case "词根" -> roots();
            case "近义词" -> synonyms();
            default -> throw new IllegalStateException("Unknown learning view");
        }
        content.removeAll(); content.add(body, BorderLayout.NORTH); content.revalidate(); content.repaint();
    }

    private void examples() {
        if (!cards("collocation").isEmpty()) {
            heading("常用搭配");
            for (CuratedItem item : cards("collocation")) {
                paragraph(item.first(), 15, Theme.INK);
                paragraph(item.second(), 13, Theme.MUTED);
                if (!item.third().isBlank()) paragraph(item.third(), 11, Theme.MUTED);
                gap(9);
            }
        }
        for (CuratedItem item : cards("example")) {
            paragraph(item.first(), 15, Theme.INK); paragraph(item.second(), 13, Theme.MUTED);
            paragraph(item.third(), 10, Theme.MUTED); gap(14);
        }
        for (SupplementItem item : supplements("practice")) {
            paragraph(item.first(), 15, Theme.INK); paragraph(item.second(), 13, Theme.MUTED);
            paragraph(item.attribution(), 10, Theme.MUTED); gap(14);
        }
        if (!supplements("example").isEmpty()) {
            heading("英汉语料例句");
            paragraph("Tatoeba 社区原译文；保留简繁体。例句可能采用词形变化或其他义项，请结合语境阅读。", 11, Theme.MUTED);
            for (SupplementItem item : supplements("example")) {
                paragraph(item.first(), 15, Theme.INK); paragraph(item.second(), 13, Theme.MUTED);
                paragraph(item.attribution(), 10, Theme.MUTED); gap(14);
            }
        }
        List<LexicalSense> senses = data.senses(word).stream().filter(s -> !s.examples().isEmpty()).toList();
        if (!senses.isEmpty()) {
            heading("词典义项例句");
            paragraph("英文原例句按词义分组，可能使用同组词；原资料未提供中文译文。", 11, Theme.MUTED);
            Set<String> seen = new HashSet<>();
            for (LexicalSense sense : limit(senses, 5)) {
                List<Example> examples = sense.examples().stream().filter(e -> seen.add(e.text())).toList();
                if (examples.isEmpty()) continue;
                heading(pos(sense.pos()) + " · " + sense.definition());
                for (Example example : examples) {
                    paragraph(example.text(), 14, Theme.INK);
                    paragraph("来源：" + example.source(), 10, Theme.MUTED); gap(9);
                }
            }
            more(senses.size(), 5);
        } else if (cards("example").isEmpty() && supplements("example").isEmpty() && supplements("practice").isEmpty()) {
            empty("暂未收录这个词的例句。", "你仍可在下方记录自己的例句；这里不会自动编写词典例句。");
        }
        curatedSources();
        openSources("example");
    }

    private void derivations() {
        paragraph("派生词是构成的新词；复数、过去式等属于词形变化。", 11, Theme.MUTED);
        Set<String> seen = new HashSet<>();
        if (!cards("derived").isEmpty()) heading("派生与同词族");
        for (CuratedItem item : cards("derived")) {
            term(item.first(), item.second() + "\n" + item.third()); seen.add(item.first().toLowerCase(Locale.ROOT));
        }
        List<String> terms = data.senses(word).stream().flatMap(s -> s.derivations().stream())
                .filter(t -> !t.equalsIgnoreCase(word)).distinct().filter(t -> seen.add(t.toLowerCase(Locale.ROOT))).toList();
        if (!terms.isEmpty()) {
            heading("词典派生关系");
            paragraph("关系可以双向连接：可能指向词基，也可能指向派生词。", 11, Theme.MUTED);
            for (String value : limit(terms, 16)) term(value, gloss(value));
            more(terms.size(), 16); wordnetSource();
        } else if (cards("derived").isEmpty() && supplements("derived").isEmpty()) {
            empty("暂未收录派生关系。", "不根据相似拼写猜测词族。");
        }
        List<SupplementItem> families = supplements("derived").stream()
                .filter(i -> seen.add(i.first().toLowerCase(Locale.ROOT))).toList();
        if (!families.isEmpty()) {
            heading("词典派生词、复合词与词组");
            paragraph("以下来自中文维基词典的派生条目；其中也包含复合词和词组，并非全部是加词缀形成的新词。", 11, Theme.MUTED);
            for (SupplementItem item : limit(families, 8)) {
                term(item.first(), item.second() + "\n" + item.attribution());
            }
            more(families.size(), 8);
        }
        curatedSources();
        openSources("derived");
    }

    private void roots() {
        for (String kind : List.of("morphology", "etymology")) {
            for (CuratedItem item : cards(kind)) {
                heading(item.first()); paragraph(item.second(), 14, Theme.INK);
            }
        }
        List<SupplementItem> explanations = supplements("etymology");
        if (!explanations.isEmpty()) {
            heading("中文词源资料");
            paragraph("保留原条目的推测和异说；历史来源与现代构词不一定相同。", 11, Theme.MUTED);
            for (SupplementItem item : limit(explanations, 2)) {
                heading(item.first()); paragraph(item.second(), 13, Theme.INK);
                paragraph(item.attribution(), 10, Theme.MUTED);
            }
            more(explanations.size(), 2);
        }
        if (!supplements("component").isEmpty()) {
            heading("构词成分的中文释义");
            paragraph("这些成分见于下方词源记录。这里列出词缀的一般含义，不能把每种含义都套到本词上。", 11, Theme.MUTED);
            for (SupplementItem item : supplements("component")) {
                heading(item.first()); paragraph(item.second(), 13, Theme.INK);
            }
        }
        if (!cards("root_related").isEmpty()) {
            heading("同源词联想");
            paragraph("历史来源相关，不代表今天的意思相近。", 11, Theme.MUTED);
            for (CuratedItem item : cards("root_related")) term(item.first(), item.second() + "\n" + item.third());
        }
        List<EtymologyLink> links = data.origins(word);
        if (!links.isEmpty()) {
            heading("词源与构词记录");
            paragraph("同形词可能有不同来源。序号保留原资料顺序，并不表示所有记录组成一条确定路径；来源链中的成分也不一定是本词的直接词根。带 * 的形式是语言学重构。", 11, Theme.MUTED);
            for (EtymologyLink link : limit(links, 12)) {
                String label = relation(link.relation()) + " · " + language(link.language());
                paragraph(label, 11, Theme.GREEN);
                String target = link.target();
                String kind = link.relation().split(" ", 2)[0];
                if (kind.equals("has_suffix") && !target.startsWith("-")) target = "-" + target;
                if (kind.equals("has_prefix") && !target.endsWith("-")) target += "-";
                paragraph(target, 16, Theme.INK); gap(10);
            }
            more(links.size(), 12);
            paragraph("来源：Wiktionary / etymology-db · 2023-12 快照 · CC BY-SA 3.0", 10, Theme.MUTED);
            String source = links.stream().map(EtymologyLink::sourceUrl).filter(s -> !s.isBlank()).findFirst()
                    .orElse("https://en.wiktionary.org/wiki/" + URLEncoder.encode(word, StandardCharsets.UTF_8).replace("+", "%20") + "#English");
            sourceLine("词源页面", source);
        } else if (cards("etymology").isEmpty() && cards("morphology").isEmpty()
                && explanations.isEmpty() && supplements("component").isEmpty()) {
            empty("暂未收录可核对的词源。", "并非所有英文单词都能拆成前缀、词根和后缀。");
        }
        curatedSources();
        openSources("etymology", "component");
    }

    private void synonyms() {
        paragraph("近义词需要结合具体词义和搭配，不能在所有句子里互换。", 11, Theme.MUTED);
        if (!cards("synonym").isEmpty()) heading("用法辨析");
        for (CuratedItem item : cards("synonym")) term(item.first(), item.second() + " · " + item.third());
        List<LexicalSense> senses = data.senses(word).stream()
                .filter(s -> s.synonyms().stream().anyMatch(w -> !w.equalsIgnoreCase(word))).toList();
        if (!senses.isEmpty()) {
            heading("按词义分组");
            for (LexicalSense sense : limit(senses, 8)) {
                heading(pos(sense.pos()) + " · " + sense.definition());
                for (String synonym : sense.synonyms()) if (!synonym.equalsIgnoreCase(word)) term(synonym, gloss(synonym));
            }
            more(senses.size(), 8); wordnetSource();
            paragraph("词旁中文来自 ECDICT，可能包含其他义项；分组以本组英文释义为准。", 10, Theme.MUTED);
        } else if (cards("synonym").isEmpty() && supplements("synonym").isEmpty()) {
            empty("暂未收录可展示的近义词。", "同一词义下没有其他词时，不会拿相关词来充数。");
        }
        List<SupplementItem> relations = supplements("synonym");
        if (!relations.isEmpty()) {
            heading("中文词典近义关系");
            paragraph("保留词典给出的词性、义项和使用标签。未标义项的关系只作查阅线索，不表示可以直接替换。", 11, Theme.MUTED);
            for (SupplementItem item : limit(relations, 8)) term(item.first(), item.second() + "\n" + item.attribution());
            more(relations.size(), 8);
        }
        curatedSources();
        openSources("synonym");
    }

    private String gloss(String value) {
        return dictionary.lookup(value).map(WordEntry::translation).filter(s -> !s.isBlank())
                .map(s -> s.length() > 160 ? s.substring(0, 160) + "…" : s).orElse("暂无中文释义");
    }
    private void term(String value, String description) {
        JButton button = Theme.button(value, false); button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setFont(Theme.latin(Font.BOLD, 14));
        boolean exists = dictionary.lookup(value).isPresent(); button.setEnabled(exists);
        button.setToolTipText(exists ? "点击查看 " + value : "当前主词库未收录此词");
        button.addActionListener(e -> navigate.accept(value)); body.add(button); gap(4);
        if (!description.isBlank()) paragraph(description, 12, Theme.MUTED); gap(12);
    }
    private void heading(String text) { gap(10); paragraph(text, 12, Theme.GREEN); gap(5); }
    private void paragraph(String text, int size, Color color) {
        JTextArea area = new WrappedText(text, size, color);
        area.setAlignmentX(Component.LEFT_ALIGNMENT); body.add(area); gap(3);
    }
    private void gap(int height) { body.add(Box.createVerticalStrut(height)); }
    private void empty(String title, String detail) { gap(12); paragraph(title, 14, Theme.INK); paragraph(detail, 12, Theme.MUTED); }
    private <T> List<T> limit(List<T> list, int limit) { return expanded.contains(selected) ? list : list.subList(0, Math.min(list.size(), limit)); }
    private void more(int count, int limit) {
        if (count <= limit) return;
        JButton button = Theme.button(expanded.contains(selected) ? "收起" : "展开其余 " + (count-limit) + " 项", false);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.addActionListener(e -> { if (!expanded.add(selected)) expanded.remove(selected); render(); });
        body.add(button); gap(10);
    }
    private void wordnetSource() { paragraph("来源：Open English WordNet 2025 / Princeton WordNet · CC BY 4.0", 10, Theme.MUTED); }
    private void curatedSources() {
        List<CuratedItem> sources = cards("source");
        if (sources.isEmpty()) return;
        gap(8); paragraph("中文学习整理与双语例句：拾词原创", 10, Theme.MUTED);
        JButton button = Theme.button(showSources ? "收起参考来源" : "参考来源（" + sources.size() + "）", false);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.addActionListener(e -> { showSources = !showSources; render(); }); body.add(button); gap(6);
        if (showSources) for (CuratedItem item : sources) sourceLine(item.first(), item.second());
    }
    private void sourceLine(String title, String url) {
        JTextArea area = new WrappedText(title + "\n" + url, 10, Theme.MUTED);
        area.setToolTipText("可选中文字复制来源地址"); body.add(area); gap(4);
    }
    private void openSources(String... kinds) {
        Set<String> included = Set.of(kinds);
        List<SupplementItem> items = data.supplemental(word).stream().filter(i -> included.contains(i.kind())).toList();
        if (items.isEmpty()) return;
        gap(8);
        JButton button = Theme.button(showOpenSources ? "收起开放资料出处" : "开放资料出处", false);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.addActionListener(e -> { showOpenSources = !showOpenSources; render(); }); body.add(button); gap(6);
        if (showOpenSources) {
            Set<String> seen = new HashSet<>();
            for (SupplementItem item : items) if (seen.add(item.attribution() + item.sourceUrl())) {
                sourceLine(item.attribution(), item.sourceUrl());
            }
            sourceLine("许可与编辑记录（亦见程序目录 licenses）",
                    "https://creativecommons.org/licenses/by/2.0/fr/\nhttps://creativecommons.org/licenses/by-sa/4.0/");
        }
    }
    private static String pos(String code) {
        return switch (code) { case "n" -> "名词"; case "v" -> "动词"; case "a", "s" -> "形容词"; case "r" -> "副词"; default -> code; };
    }
    private static String relation(String value) {
        int annotation = value.indexOf('[');
        String kind = (annotation < 0 ? value : value.substring(0, annotation)).strip();
        String label = switch (kind) {
            case "borrowed_from", "borrowing_from" -> "借自";
            case "learned_borrowing_from" -> "学术借词来源";
            case "semi_learned_borrowing_from" -> "半学术借词来源";
            case "unadapted_borrowing_from" -> "未调整形式的借词来源";
            case "orthographic_borrowing_from" -> "借用书写形式";
            case "derived_from", "derivation" -> "来源于";
            case "inherited_from" -> "继承自";
            case "has_prefix" -> "前缀";
            case "has_suffix" -> "后缀";
            case "has_prefix_with_root", "has_suffix_with_root" -> "构词词基";
            case "has_affix", "has_confix" -> "构词成分";
            case "has_root", "root" -> "词根记录";
            case "compound_of", "compound" -> "复合成分";
            case "is_related_to" -> "相关记录";
            case "etymologically_related_to" -> "词源相关成分";
            case "doublet_with" -> "同源异形词";
            case "named_after" -> "得名于";
            case "clipping_of" -> "截短自";
            case "abbreviation_of" -> "缩写自";
            case "initialism_of" -> "首字母缩写来源";
            case "blend_of" -> "混成词成分";
            case "back-formation_from" -> "逆构来源";
            case "calque_of" -> "仿译来源";
            case "semantic_loan_of" -> "语义借用来源";
            case "phono-semantic_matching_of" -> "音义匹配来源";
            default -> kind.replace('_', ' ');
        };
        return label + (annotation < 0 ? "" : " " + value.substring(annotation));
    }
    private static String language(String value) {
        return switch (value) {
            case "en", "eng", "English" -> "英语";
            case "la", "lat", "Latin" -> "拉丁语";
            case "fr", "fra", "fre", "French" -> "法语";
            case "grc", "Ancient Greek" -> "古希腊语";
            case "el", "ell", "Greek" -> "希腊语";
            case "enm", "Middle English" -> "中古英语";
            case "ang", "Old English" -> "古英语";
            case "fro", "Old French" -> "古法语";
            case "de", "deu", "German" -> "德语";
            case "it", "ita", "Italian" -> "意大利语";
            case "ar", "ara", "Arabic" -> "阿拉伯语";
            case "ine-pro", "Proto-Indo-European" -> "原始印欧语（重构）";
            case "Proto-Germanic" -> "原始日耳曼语（重构）";
            case "Proto-West Germanic" -> "原始西日耳曼语（重构）";
            case "Proto-Italic" -> "原始意大利语族语（重构）";
            default -> value;
        };
    }

    /** Computes wrapping from the actual layout width instead of an estimated row count. */
    private static final class WrappedText extends JTextArea {
        WrappedText(String text, int size, Color color) {
            super(text); setEditable(false); setOpaque(false); setLineWrap(true); setWrapStyleWord(true);
            setFont(Theme.font(Font.PLAIN, size)); setForeground(color); setBorder(null);
        }
        @Override public Dimension getPreferredSize() {
            int available = getParent() == null ? 330 : getParent().getWidth();
            if (available <= 0) available = 330;
            setSize(available, Short.MAX_VALUE);
            Dimension size = super.getPreferredSize(); size.width = 1; return size;
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override public Dimension getMinimumSize() { return new Dimension(1, getPreferredSize().height); }
    }
}
