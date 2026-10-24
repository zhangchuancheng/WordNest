package wordnest;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
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
    private final Consumer<String> speak;
    private final JPanel content = Theme.panel(new BorderLayout());
    private final Set<String> expanded = new HashSet<>();
    private String selected = "例句";
    private JPanel body;

    LearningPanel(String word, LearningRepository data, DictionaryRepository dictionary, Consumer<String> navigate) {
        this(word, data, dictionary, navigate, null);
    }

    LearningPanel(String word, LearningRepository data, DictionaryRepository dictionary,
                  Consumer<String> navigate, Consumer<String> speak) {
        super(new BorderLayout(0, 14));
        this.word = word; this.data = data; this.dictionary = dictionary; this.navigate = navigate; this.speak = speak;
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
        List<ExampleSelection.Candidate> candidates = new ArrayList<>();
        for (CuratedItem item : cards("example")) candidates.add(new ExampleSelection.Candidate(
                item.first(), item.second(), "词巢原创 · 非考试真题", item.third(), "", word, ExampleSelection.SourceKind.CURATED));
        for (SupplementItem item : supplements("practice")) candidates.add(new ExampleSelection.Candidate(
                item.first(), item.second(), "词巢原创学习例句 · 非考试真题", brandLegacyLabel(item.attribution()),
                brandLegacyLabel(item.sourceUrl()), word, ExampleSelection.SourceKind.PRACTICE));
        for (SupplementItem item : supplements("example")) {
            String form = word;
            int marker = item.attribution().indexOf("例句词形：");
            if (marker >= 0) form = item.attribution().substring(marker + "例句词形：".length()).strip();
            candidates.add(new ExampleSelection.Candidate(item.first(), item.second(), "语料 · Tatoeba",
                    item.attribution(), item.sourceUrl(), form, ExampleSelection.SourceKind.CORPUS));
        }
        ExampleSelection.Result selection = ExampleSelection.select(word, candidates);
        List<ExampleSelection.Group> additional = selection.additional();
        if (!selection.recommended().isEmpty()) {
            heading("推荐双语例句 · " + selection.recommended().size());
            for (int i = 0; i < selection.recommended().size(); i++)
                bilingualCard(selection.recommended().get(i), i + 1, "example-source-" + i);
        } else if (!additional.isEmpty()) {
            heading("双语语料参考");
            paragraph("暂无符合短句筛选条件的条目，先保留一条供参考。", 11, Theme.MUTED);
            bilingualCard(additional.get(0), 1, "example-source-0");
            additional = additional.subList(1, additional.size());
        }
        if (!additional.isEmpty()) {
            List<ExampleSelection.Group> moreExamples = additional;
            disclosure("examples-bilingual-more", "更多语料（" + moreExamples.size() + "）", "收起更多语料", false, () -> {
                paragraph("包含其余例句、长句和需要补充语境的条目；原句与原译文保持不变。", 11, Theme.MUTED);
                List<ExampleSelection.Group> visible = limit(moreExamples, 6, "examples-more-items");
                for (int i = 0; i < visible.size(); i++) bilingualCard(visible.get(i), i + 1, "example-more-source-" + i);
                more(moreExamples.size(), 6, "examples-more-items");
            });
        }
        if (!candidates.isEmpty()) disclosure("examples-selection", "筛选依据与来源说明", "收起筛选说明", false, () -> {
            paragraph("优先展示含查询词或来源明确标注词形、3–24 个英文词、180 字符以内、带句末标点且无省略或分段的句子。同等匹配条件下，先展示已有中文学习卡例句，其次原创练习和社区语料；同类再按长度排序。", 11, Theme.MUTED);
            paragraph("这些可检查的信号只用于阅读排序，不判断语法、自然度或译文准确性，也不代表人工审校。社区例句没有对应的词典义项标注，不自动归入下方义项。", 11, Theme.MUTED);
            paragraph("大小写、空白与标点差异相同的英文合并展示，保留缩略形式与单词边界。原始 " + selection.rawCount()
                    + " 条记录整理为 " + selection.uniqueCount() + " 组；所有不同的原句、原译文和来源都可在各卡出处中查看。", 11, Theme.MUTED);
        });

        List<SenseExamples> direct = senseExamples(true);
        List<SenseExamples> related = senseExamples(false);
        if (!direct.isEmpty()) disclosure("examples-english", "英文例句（" + direct.size() + " 个义项）", "收起英文例句", candidates.isEmpty(), () -> {
            paragraph("按词典原始义项分组；原资料未提供中文译文。双语语料不自动对应这些义项。", 11, Theme.MUTED);
            wordnetExamples(direct, "english");
        });
        if (!related.isEmpty()) disclosure("examples-related", "补充英文例句（" + related.size() + " 个义项）", "收起补充英文例句", false, () -> {
            paragraph("这些句子未使用当前查询词的拼写，可能使用词形变化或同义词。原资料未提供中文译文。", 11, Theme.MUTED);
            wordnetExamples(related, "related");
        });
        if (candidates.isEmpty() && direct.isEmpty()) empty("暂未收录这个词的直接例句。", related.isEmpty()
                ? "可在下方记下你遇到的原句。" : "可展开补充英文例句作为参考。");
        if (!cards("collocation").isEmpty()) disclosure("examples-collocations", "常用搭配", "收起常用搭配", false, () -> {
            for (CuratedItem item : cards("collocation")) card(() -> {
                paragraph(item.first(), 14, Theme.INK);
                paragraph(item.second(), 12, Theme.MUTED);
                if (!item.third().isBlank()) paragraph(item.third(), 11, Theme.MUTED);
            });
        });
        curatedSources();
    }

    private void bilingualCard(ExampleSelection.Group group, int number, String sourceKey) {
        ExampleSelection.Candidate item = group.primary();
        card(() -> {
            paragraph(String.format("%02d", number) + "  " + item.label(), 10, Theme.GREEN);
            gap(5);
            sentence(item.english(), group.matchedForm());
            paragraph(item.chinese(), 13, Theme.INK);
            if (!group.matchedForm().isBlank() && !group.matchedForm().equalsIgnoreCase(word))
                paragraph("例句词形：" + group.matchedForm(), 10, Theme.MUTED);
            if (!group.notes().isEmpty()) paragraph(String.join("；", group.notes()) + "。", 10, Theme.MUTED);
            String label = group.originals().size() == 1 ? "出处" : "出处与原译文（" + group.originals().size() + " 条记录）";
            disclosure(sourceKey, label, "收起出处", false, () -> {
                for (int i = 0; i < group.originals().size(); i++) {
                    ExampleSelection.Candidate original = group.originals().get(i);
                    if (group.originals().size() > 1) {
                        heading("原始记录 " + (i + 1) + " · " + original.label());
                        paragraph(original.english(), 12, Theme.INK);
                        paragraph(original.chinese(), 12, Theme.INK);
                    }
                    paragraph(original.attribution(), 10, Theme.MUTED);
                    if (!original.url().isBlank()) sourceLine("原句与译文", original.url());
                    if (!original.original()) {
                        paragraph("社区原译文保留简繁体，请结合语境阅读。", 10, Theme.MUTED);
                        sourceLine("许可与编辑记录（亦见程序目录 licenses）", "https://creativecommons.org/licenses/by/2.0/fr/");
                    }
                }
            });
        });
    }

    private record SenseExamples(LexicalSense sense, int number, List<List<Example>> sentences) { }

    /** Duplicate normalization is confined to a real source sense, never inferred from bilingual text. */
    private List<SenseExamples> senseExamples(boolean direct) {
        List<SenseExamples> result = new ArrayList<>();
        List<LexicalSense> senses = data.senses(word);
        for (int i = 0; i < senses.size(); i++) {
            LexicalSense sense = senses.get(i);
            Map<String, List<Example>> unique = new LinkedHashMap<>();
            for (Example example : sense.examples()) {
                if (ExampleSelection.containsWord(example.text(), word) != direct) continue;
                unique.computeIfAbsent(ExampleSelection.normalizedSentence(example.text()), ignored -> new ArrayList<>()).add(example);
            }
            if (unique.isEmpty()) continue;
            List<List<Example>> grouped = new ArrayList<>();
            for (List<Example> examples : unique.values()) grouped.add(examples.stream().distinct().toList());
            grouped.sort(Comparator.comparingInt(examples -> ExampleSelection.wordCount(examples.get(0).text())));
            result.add(new SenseExamples(sense, i + 1, List.copyOf(grouped)));
        }
        return result;
    }

    private void wordnetExamples(List<SenseExamples> senses, String key) {
        List<SenseExamples> visible = limit(senses, 3, "examples-" + key + "-senses");
        for (int i = 0; i < visible.size(); i++) {
            SenseExamples row = visible.get(i);
            String senseKey = "examples-" + key + "-sense-" + i;
            card(() -> {
                body.setName("wordnet-sense-" + key + "-" + row.number());
                heading(pos(row.sense().pos()) + " · 义项 " + row.number());
                paragraph(row.sense().definition(), 12, Theme.INK);
                List<List<Example>> sentences = limit(row.sentences(), 3, senseKey + "-items");
                for (int j = 0; j < sentences.size(); j++) {
                    List<Example> originals = sentences.get(j);
                    gap(7);
                    sentence(originals.get(0).text(), word);
                    disclosure(senseKey + "-source-" + j, "出处", "收起出处", false, () -> {
                        paragraph("词典义项编号：" + row.sense().id(), 10, Theme.MUTED);
                        for (Example original : originals) {
                            if (originals.size() > 1) paragraph(original.text(), 12, Theme.INK);
                            paragraph(original.source(), 10, Theme.MUTED);
                        }
                        wordnetSource();
                    });
                }
                more(row.sentences().size(), 3, senseKey + "-items");
            });
        }
        more(senses.size(), 3, "examples-" + key + "-senses");
    }

    private void sentence(String text, String form) {
        JTextArea area = new WrappedText(text, 16, Theme.INK);
        area.setFont(Theme.latin(Font.PLAIN, 16));
        area.setName("example-english");
        if (!form.isBlank()) {
            var matches = ExampleSelection.wordPattern(form).matcher(text);
            while (matches.find()) try {
                area.getHighlighter().addHighlight(matches.start(), matches.end(),
                        new DefaultHighlighter.DefaultHighlightPainter(Theme.PALE));
            } catch (javax.swing.text.BadLocationException impossible) { throw new IllegalStateException(impossible); }
        }
        body.add(area); gap(7);
        if (speak != null) {
            JButton play = Theme.button("朗读例句", false);
            play.setName("speak-example"); play.setFont(Theme.font(Font.PLAIN, 11));
            play.setBorder(new EmptyBorder(4, 9, 4, 9));
            play.getAccessibleContext().setAccessibleDescription(text);
            play.addActionListener(event -> speak.accept(text));
            body.add(play); gap(7);
        }
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
            paragraph("中文学习整理与双语例句：词巢原创", 10, Theme.MUTED);
            for (CuratedItem item : sources) sourceLine(item.first(), item.second());
        });
    }
    private void sourceLine(String title, String url) {
        JTextArea area = new WrappedText(brandLegacyLabel(title) + "\n" + brandLegacyLabel(url), 10, Theme.MUTED);
        area.setToolTipText("可选中文字复制来源地址"); body.add(area); gap(4);
    }
    private static String brandLegacyLabel(String text) {
        return text.replace("拾词原创学习例句", "词巢原创学习例句");
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
        private Font measuredFont;
        private FontMetrics measuredMetrics;
        WrappedText(String text, int size, Color color) {
            super(text); setEditable(false); setOpaque(false); setLineWrap(true); setWrapStyleWord(true);
            setFont(Theme.font(Font.PLAIN, size)); setForeground(color); setBorder(null);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        /** Fractional advances do not change when a window moves between screen scale factors. */
        @Override public FontMetrics getFontMetrics(Font font) {
            if (!font.equals(measuredFont)) {
                Graphics2D metrics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
                try {
                    textHints(metrics);
                    measuredMetrics = metrics.getFontMetrics(font);
                    measuredFont = font;
                } finally { metrics.dispose(); }
            }
            return measuredMetrics;
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D text = (Graphics2D) graphics.create();
            try { textHints(text); super.paintComponent(text); } finally { text.dispose(); }
        }
        private static void textHints(Graphics2D graphics) {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
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
