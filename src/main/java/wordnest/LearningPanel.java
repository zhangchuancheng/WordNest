package wordnest;

import javax.swing.*;
import java.awt.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.swing.border.EmptyBorder;
import javax.swing.text.DefaultHighlighter;
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
        body = new ReadingColumn();
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
        List<ExampleCard> bilingual = new ArrayList<>();
        for (CuratedItem item : cards("example")) {
            bilingual.add(new ExampleCard(item.first(), item.second(), "拾词原创 · 非考试真题", item.third(), "", word));
        }
        for (SupplementItem item : supplements("practice")) {
            bilingual.add(new ExampleCard(item.first(), item.second(), "拾词原创学习例句 · 非考试真题", item.attribution(), item.sourceUrl(), word));
        }
        for (SupplementItem item : supplements("example")) {
            String form = word;
            int marker = item.attribution().indexOf("例句词形：");
            if (marker >= 0) form = item.attribution().substring(marker + "例句词形：".length()).strip();
            bilingual.add(new ExampleCard(item.first(), item.second(), "语料 · Tatoeba", item.attribution(), item.sourceUrl(), form));
        }
        if (!bilingual.isEmpty()) {
            heading("双语例句 · " + bilingual.size());
            List<ExampleCard> visible = limit(bilingual, 3, "examples-bilingual");
            for (int i = 0; i < visible.size(); i++) {
                ExampleCard item = visible.get(i);
                int index = i;
                card(() -> {
                    paragraph(String.format("%02d", index + 1) + "  " + item.label(), 10, Theme.GREEN);
                    gap(5);
                    sentence(item.english(), item.form());
                    paragraph(item.chinese(), 13, Theme.INK);
                    if (!item.form().equalsIgnoreCase(word)) paragraph("例句词形：" + item.form(), 10, Theme.MUTED);
                    disclosure("example-source-" + index, "出处", "收起出处", false, () -> {
                        paragraph(item.attribution(), 10, Theme.MUTED);
                        if (!item.url().isBlank()) sourceLine("原句与译文", item.url());
                        if (item.label().contains("Tatoeba")) {
                            paragraph("社区原译文保留简繁体，请结合语境阅读。", 10, Theme.MUTED);
                            sourceLine("许可与编辑记录（亦见程序目录 licenses）", "https://creativecommons.org/licenses/by/2.0/fr/");
                        }
                    });
                });
            }
            more(bilingual.size(), 3, "examples-bilingual");
        }

        List<SenseExample> direct = new ArrayList<>();
        List<SenseExample> related = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (LexicalSense sense : data.senses(word)) for (Example example : sense.examples()) {
            // WordNet examples describe a synset, and need not contain the queried headword.
            // Keep the original sense and credit, including distinct senses sharing a sentence.
            if (!seen.add(sense.id() + "\n" + example.text() + "\n" + example.source())) continue;
            (containsWord(example.text(), word) ? direct : related).add(new SenseExample(sense, example));
        }
        if (!direct.isEmpty()) {
            disclosure("examples-english", "英文例句（" + direct.size() + "）", "收起英文例句", bilingual.isEmpty(), () -> {
                paragraph("原资料未提供中文译文。", 11, Theme.MUTED);
                wordnetExamples(direct, "english");
            });
        }
        if (!related.isEmpty()) {
            disclosure("examples-related", "补充英文例句（" + related.size() + "）", "收起补充英文例句", false, () -> {
                paragraph("这些句子未使用当前查询词的拼写，可能使用词形变化或同义词。原资料未提供中文译文。", 11, Theme.MUTED);
                wordnetExamples(related, "related");
            });
        }
        if (bilingual.isEmpty() && direct.isEmpty()) {
            empty("暂未收录这个词的直接例句。", related.isEmpty()
                    ? "可在下方记下你遇到的原句。" : "可展开补充英文例句作为参考。");
        }
        if (!cards("collocation").isEmpty()) disclosure("examples-collocations", "常用搭配", "收起常用搭配", false, () -> {
            for (CuratedItem item : cards("collocation")) card(() -> {
                paragraph(item.first(), 14, Theme.INK);
                paragraph(item.second(), 12, Theme.MUTED);
                if (!item.third().isBlank()) paragraph(item.third(), 11, Theme.MUTED);
            });
        });
        curatedSources();
    }

    private record ExampleCard(String english, String chinese, String label, String attribution, String url, String form) { }
    private record SenseExample(LexicalSense sense, Example example) { }

    private void wordnetExamples(List<SenseExample> examples, String key) {
        List<SenseExample> visible = limit(examples, 3, "examples-" + key + "-items");
        for (int i = 0; i < visible.size(); i++) {
            SenseExample row = visible.get(i);
            int index = i;
            card(() -> {
                sentence(row.example().text(), word);
                paragraph(pos(row.sense().pos()) + " · " + row.sense().definition(), 11, Theme.MUTED);
                disclosure("example-" + key + "-source-" + index, "出处", "收起出处", false, () -> {
                    paragraph(row.example().source(), 10, Theme.MUTED);
                    wordnetSource();
                });
            });
        }
        more(examples.size(), 3, "examples-" + key + "-items");
    }

    private static Pattern wordPattern(String value) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(value.strip()) + "(?![\\p{L}\\p{N}_])");
    }
    private static boolean containsWord(String text, String value) {
        return !value.isBlank() && wordPattern(value).matcher(text).find();
    }
    private void sentence(String text, String form) {
        JTextArea area = new WrappedText(text, 16, Theme.INK);
        area.setFont(Theme.latin(Font.PLAIN, 16));
        area.setName("example-english");
        if (!form.isBlank()) {
            var matches = wordPattern(form).matcher(text);
            while (matches.find()) try {
                area.getHighlighter().addHighlight(matches.start(), matches.end(),
                        new DefaultHighlighter.DefaultHighlightPainter(Theme.PALE));
            } catch (javax.swing.text.BadLocationException impossible) { throw new IllegalStateException(impossible); }
        }
        body.add(area); gap(7);
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
            for (String value : limit(terms, 6, "derived-wordnet")) term(value, gloss(value));
            more(terms.size(), 6, "derived-wordnet");
        } else if (cards("derived").isEmpty() && supplements("derived").isEmpty()) {
            empty("暂未收录派生关系。", "不根据相似拼写猜测词族。");
        }
        List<SupplementItem> families = supplements("derived").stream()
                .filter(i -> seen.add(i.first().toLowerCase(Locale.ROOT))).toList();
        if (!families.isEmpty()) {
            heading("词典派生词、复合词与词组");
            paragraph("以下来自中文维基词典的派生条目；其中也包含复合词和词组，并非全部是加词缀形成的新词。", 11, Theme.MUTED);
            for (SupplementItem item : limit(families, 5, "derived-open")) {
                term(item.first(), item.second());
            }
            more(families.size(), 5, "derived-open");
        }
        if (!terms.isEmpty()) disclosure("derived-credit", "词典资料出处", "收起词典资料出处", false, this::wordnetSource);
        curatedSources();
        openSources("derived");
    }

    private void roots() {
        for (String kind : List.of("morphology", "etymology")) {
            for (CuratedItem item : cards(kind)) {
                card(() -> { heading(item.first()); paragraph(item.second(), 14, Theme.INK); });
            }
        }
        List<SupplementItem> explanations = supplements("etymology");
        if (!explanations.isEmpty()) {
            heading("中文词源资料");
            paragraph("保留原条目的推测和异说；历史来源与现代构词不一定相同。", 11, Theme.MUTED);
            for (SupplementItem item : limit(explanations, 2, "roots-chinese")) {
                card(() -> { heading(item.first()); paragraph(item.second(), 13, Theme.INK); });
            }
            more(explanations.size(), 2, "roots-chinese");
        }
        if (!supplements("component").isEmpty()) {
            heading("构词成分的中文释义");
            paragraph("这些成分见于下方词源记录。这里列出词缀的一般含义，不能把每种含义都套到本词上。", 11, Theme.MUTED);
            for (SupplementItem item : supplements("component")) {
                card(() -> { heading(item.first()); paragraph(item.second(), 13, Theme.INK); });
            }
        }
        if (!cards("root_related").isEmpty()) {
            heading("同源词联想");
            paragraph("历史来源相关，不代表今天的意思相近。", 11, Theme.MUTED);
            for (CuratedItem item : cards("root_related")) term(item.first(), item.second() + "\n" + item.third());
        }
        List<EtymologyLink> links = data.origins(word);
        if (!links.isEmpty()) {
            disclosure("roots-records", "词源原始记录（" + links.size() + "）", "收起词源原始记录", false, () -> {
            paragraph("同形词可能有不同来源。序号保留原资料顺序，并不表示所有记录组成一条确定路径；来源链中的成分也不一定是本词的直接词根。带 * 的形式是语言学重构。", 11, Theme.MUTED);
            for (EtymologyLink link : limit(links, 6, "roots-record-items")) {
                card(() -> {
                String label = relation(link.relation()) + " · " + language(link.language());
                paragraph(label, 11, Theme.GREEN);
                String target = link.target();
                String kind = link.relation().split(" ", 2)[0];
                if (kind.equals("has_suffix") && !target.startsWith("-")) target = "-" + target;
                if (kind.equals("has_prefix") && !target.endsWith("-")) target += "-";
                paragraph(target, 16, Theme.INK);
                });
            }
            more(links.size(), 6, "roots-record-items");
            paragraph("来源：Wiktionary / etymology-db · 2023-12 快照 · CC BY-SA 3.0", 10, Theme.MUTED);
            String source = links.stream().map(EtymologyLink::sourceUrl).filter(s -> !s.isBlank()).findFirst()
                    .orElse("https://en.wiktionary.org/wiki/" + URLEncoder.encode(word, StandardCharsets.UTF_8).replace("+", "%20") + "#English");
            sourceLine("词源页面", source);
            });
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
            for (LexicalSense sense : limit(senses, 3, "synonyms-wordnet")) {
                card(() -> {
                paragraph(pos(sense.pos()) + " · " + sense.definition(), 12, Theme.INK);
                gap(6);
                for (String synonym : sense.synonyms()) if (!synonym.equalsIgnoreCase(word)) term(synonym, gloss(synonym));
                });
            }
            more(senses.size(), 3, "synonyms-wordnet");
            paragraph("词旁中文来自 ECDICT，可能包含其他义项；分组以本组英文释义为准。", 10, Theme.MUTED);
        } else if (cards("synonym").isEmpty() && supplements("synonym").isEmpty()) {
            empty("暂未收录可展示的近义词。", "同一词义下没有其他词时，不会拿相关词来充数。");
        }
        List<SupplementItem> relations = supplements("synonym");
        if (!relations.isEmpty()) {
            heading("中文词典近义关系");
            paragraph("保留词典给出的词性、义项和使用标签。未标义项的关系只作查阅线索，不表示可以直接替换。", 11, Theme.MUTED);
            for (SupplementItem item : limit(relations, 5, "synonyms-open")) term(item.first(), item.second());
            more(relations.size(), 5, "synonyms-open");
        }
        if (!senses.isEmpty()) disclosure("synonyms-credit", "词典资料出处", "收起词典资料出处", false, this::wordnetSource);
        curatedSources();
        openSources("synonym");
    }

    private String gloss(String value) {
        return dictionary.lookup(value).map(WordEntry::translation).filter(s -> !s.isBlank())
                .map(s -> s.length() > 160 ? s.substring(0, 160) + "…" : s).orElse("暂无中文释义");
    }
    private void term(String value, String description) {
        if ("learning-card".equals(body.getName())) termContent(value, description);
        else card(() -> termContent(value, description));
    }
    private void termContent(String value, String description) {
        JButton button = Theme.button(value, false); button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setFont(Theme.latin(Font.BOLD, 14));
        boolean exists = dictionary.lookup(value).isPresent(); button.setEnabled(exists);
        button.setToolTipText(exists ? "点击查看 " + value : "当前主词库未收录此词");
        button.addActionListener(e -> navigate.accept(value)); body.add(button); gap(4);
        if (!description.isBlank()) paragraph(description, 12, Theme.INK);
        gap(6);
    }
    private void heading(String text) {
        gap(8);
        JTextArea area = new WrappedText(text, 13, Theme.GREEN);
        area.setFont(Theme.font(Font.BOLD, 13)); body.add(area); gap(8);
    }
    private void paragraph(String text, int size, Color color) {
        JTextArea area = new WrappedText(text, size, color);
        area.setAlignmentX(Component.LEFT_ALIGNMENT); body.add(area); gap(3);
    }
    private void gap(int height) { body.add(Box.createVerticalStrut(height)); }
    private void empty(String title, String detail) { gap(12); paragraph(title, 14, Theme.INK); paragraph(detail, 12, Theme.MUTED); }
    private String stateKey(String key) { return selected + ":" + key; }
    private boolean isExpanded(String key) { return expanded.contains(stateKey(key)); }
    private <T> List<T> limit(List<T> list, int limit, String key) {
        return isExpanded(key) ? list : list.subList(0, Math.min(list.size(), limit));
    }
    private void more(int count, int limit, String key) {
        if (count <= limit) return;
        JButton button = textButton(isExpanded(key) ? "收起" : "展开其余 " + (count-limit) + " 项");
        button.setName(key + "-more");
        button.addActionListener(e -> { toggleState(key); render(); });
        body.add(button); gap(10);
    }
    private void toggleState(String key) { if (!expanded.add(stateKey(key))) expanded.remove(stateKey(key)); }
    private JButton textButton(String label) {
        JButton button = new JButton(label);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setFont(Theme.font(Font.PLAIN, 11)); button.setForeground(Theme.GREEN);
        button.setContentAreaFilled(false); button.setBorder(new EmptyBorder(6, 0, 6, 2));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }
    private void disclosure(String key, String closed, String open, boolean defaultOpen, Runnable contents) {
        boolean visible = defaultOpen != isExpanded(key);
        JButton button = textButton(visible ? open : closed);
        button.setName(key);
        button.getAccessibleContext().setAccessibleDescription(visible ? "已展开，按空格收起" : "已收起，按空格展开");
        button.addActionListener(e -> { toggleState(key); render(); });
        body.add(button);
        if (visible) contents.run();
        gap(4);
    }
    /** Each item keeps its own readable block while retaining selectable source text. */
    private void card(Runnable contents) {
        JPanel parent = body;
        JPanel item = new ReadingColumn();
        item.setName("learning-card");
        item.setOpaque(true); item.setBackground(Theme.PAPER);
        item.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.LINE), new EmptyBorder(10, 12, 8, 12)));
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        body = item;
        try { contents.run(); } finally { body = parent; }
        parent.add(item); gap(10);
    }
    private void wordnetSource() { paragraph("来源：Open English WordNet 2025 / Princeton WordNet · CC BY 4.0", 10, Theme.MUTED); }
    private void curatedSources() {
        List<CuratedItem> sources = cards("source");
        if (sources.isEmpty()) return;
        disclosure("curated-sources", "参考来源（" + sources.size() + "）", "收起参考来源", false, () -> {
            paragraph("中文学习整理与双语例句：拾词原创", 10, Theme.MUTED);
            for (CuratedItem item : sources) sourceLine(item.first(), item.second());
        });
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
        disclosure("open-sources", "开放资料出处", "收起开放资料出处", false, () -> {
            Set<String> seen = new HashSet<>();
            for (SupplementItem item : items) if (seen.add(item.attribution() + item.sourceUrl())) {
                sourceLine(item.attribution(), item.sourceUrl());
            }
            sourceLine("许可与编辑记录（亦见程序目录 licenses）",
                    "https://creativecommons.org/licenses/by/2.0/fr/\nhttps://creativecommons.org/licenses/by-sa/4.0/");
        });
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

    /** Measures every nested block at its assigned width; BoxLayout caches stale wrapped heights on resize. */
    private static final class ReadingColumn extends JPanel {
        ReadingColumn() { super(null); setOpaque(false); setAlignmentX(Component.LEFT_ALIGNMENT); }
        private int availableWidth() {
            Insets padding = getInsets();
            return Math.max(1, (getWidth() > 0 ? getWidth() : 330) - padding.left - padding.right);
        }
        private Dimension measure(Component child, int width) {
            child.setSize(width, Math.max(1, child.getHeight()));
            return child.getPreferredSize();
        }
        @Override public Dimension getPreferredSize() {
            Insets padding = getInsets();
            int height = padding.top + padding.bottom;
            for (Component child : getComponents()) if (child.isVisible()) height += measure(child, availableWidth()).height;
            return new Dimension(1, height);
        }
        @Override public Dimension getMinimumSize() { return new Dimension(1, getPreferredSize().height); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override public void doLayout() {
            Insets padding = getInsets(); int y = padding.top; int width = availableWidth();
            for (Component child : getComponents()) if (child.isVisible()) {
                Dimension preferred = measure(child, width);
                int childWidth = child instanceof JButton ? Math.min(width, preferred.width) : width;
                child.setBounds(padding.left, y, childWidth, preferred.height);
                y += preferred.height;
            }
        }
    }

    /** Computes wrapping from the actual layout width instead of an estimated row count. */
    private static final class WrappedText extends JTextArea {
        WrappedText(String text, int size, Color color) {
            super(text); setEditable(false); setOpaque(false); setLineWrap(true); setWrapStyleWord(true);
            setFont(Theme.font(Font.PLAIN, size)); setForeground(color); setBorder(null);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        @Override public Dimension getPreferredSize() {
            Container parent = getParent();
            int available = parent == null ? 330 : parent.getWidth() - parent.getInsets().left - parent.getInsets().right;
            if (available <= 0) available = 330;
            setSize(available, Short.MAX_VALUE);
            Dimension size = super.getPreferredSize(); size.width = 1; return size;
        }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override public Dimension getMinimumSize() { return new Dimension(1, getPreferredSize().height); }
    }
}
