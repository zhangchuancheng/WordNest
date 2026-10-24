package wordnest;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Pure JPanel tests and real Swing offscreen previews. Never creates a desktop window. */
public final class LearningPanelTest {
    private static final int[] WIDTHS = {340, 440, 640};
    private static final List<String> TABS = List.of("例句", "派生", "词根", "近义词");
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Run with -Djava.awt.headless=true");
        Path output = Path.of(args[0]); Files.createDirectories(output);
        DictionaryRepository dictionary = DictionaryTestResources.load();
        LearningRepository learning = LearningRepository.load();
        LearningRepository grouped = fixture(true);
        LearningRepository englishOnly = fixture(false);
        LearningRepository quality = qualityFixture();
        LearningRepository fallback = fromRows(List.of(),
                "example\trun\tRun!\t跑！\tOriginal short expression\n"
                + "example\trun\ta run in the park\t公园里的一次跑步\tOriginal fragment\n");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            try {
                check(Window.getWindows().length == 0, "No windows exist before panel tests");
                certificate(learning, dictionary, output);
                scaledSentenceWrapping(learning, dictionary, output);
                progressiveDisclosure(grouped, englishOnly, dictionary, output);
                qualityAndSenses(quality, fallback, dictionary, output);
                referencesAndNavigation(learning, dictionary);
                bundledMaterial(learning, dictionary, output);
                check(Window.getWindows().length == 0, "No windows created by rendering or button actions");
                System.out.println("PASS: " + assertions + " headless learning UI assertions; Java "
                        + System.getProperty("java.version") + "; widths 340/440/640; no windows created");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static void certificate(LearningRepository learning, DictionaryRepository dictionary, Path output) throws Exception {
        LearningPanel panel = new LearningPanel("certificate", learning, dictionary, ignored -> { });
        String initial = text(panel);
        check(initial.contains("Where's my birth certificate?"), "Screenshot example remains visible");
        check(initial.contains("我的出生证明呢"), "Screenshot translation remains visible");
        check(!initial.contains("he held several valuable securities"), "Unrelated WordNet sentence is folded initially");
        check(!initial.contains("a formal declaration that documents a fact"), "Long English sense does not interrupt the bilingual example");
        check(!initial.contains("ddhktr") && !initial.contains("9969449") && !initial.contains("https://"),
                "Author, corpus ID and URL are behind source disclosure");
        check(area(panel, "Where's my birth certificate?").getHighlighter().getHighlights().length > 0,
                "Current word is highlighted in the real example");
        for (int width : WIDTHS) render(panel, width, output.resolve("certificate-default-" + width + ".png"));
        namedButton(panel, "example-source-0").doClick();
        String opened = text(panel);
        check(opened.contains("Where's my birth certificate?") && opened.contains("我的出生证明呢"),
                "Showing a source keeps both sentences visible");
        var source = learning.supplemental("certificate").stream().filter(i -> i.kind().equals("example")).findFirst().orElseThrow();
        check(opened.contains(source.attribution()) && opened.contains("9969449") && opened.contains("CC BY 2.0"),
                "Corpus identifier, author and license remain available: " + opened);
        check(opened.contains("https://tatoeba.org/en/sentences/show/"), "Offline source URL can be copied");
        check(opened.contains("https://creativecommons.org/licenses/by/2.0/fr/"), "Corpus license URL remains available");
        for (int width : WIDTHS) render(panel, width, output.resolve("certificate-source-" + width + ".png"));
        namedButton(panel, "example-source-0").doClick();
        check(!text(panel).contains("9969449"), "Source can be collapsed again");
        namedButton(panel, "examples-related").doClick();
        check(text(panel).contains("he held several valuable securities"), "Original related sentence is available on request");
        check(text(panel).contains("未使用当前查询词的拼写"), "Related examples explain the absent queried spelling");
        check(text(panel).contains("原资料未提供中文译文"), "English-only status is explicit");
        for (int width : WIDTHS) render(panel, width, output.resolve("certificate-related-" + width + ".png"));
        namedButton(panel, "examples-related").doClick();
        check(!text(panel).contains("he held several valuable securities"), "Related examples collapse without removing bilingual material");
        check(text(panel).contains("Where's my birth certificate?"), "Bilingual example survives other section toggles");
    }

    private static void scaledSentenceWrapping(LearningRepository learning, DictionaryRepository dictionary, Path output) throws Exception {
        LearningPanel panel = new LearningPanel("relevant", learning, dictionary, ignored -> { });
        String sentence = "Her experience with databases is relevant to this internship.";
        var english = components(panel).stream().filter(c -> c instanceof JTextArea && "example-english".equals(c.getName()))
                .map(c -> ((JTextArea)c).getText()).toList();
        check(english.get(0).equals(sentence), "An eligible contextual learning card precedes a shorter generic community sentence");
        for (int width : WIDTHS) {
            render(panel, width, output.resolve("relevant-default-" + width + ".png"));
            for (double scale : new double[]{1, 1.25, 1.5, 2}) assertRenderedTextWidths(panel, scale);
            if (width == 440) {
                JTextArea area = area(panel, sentence);
                check(area.modelToView2D(sentence.length()).getY() > 0,
                        "The 414px text area wraps the relevant internship sentence rather than clipping its final word");
            }
        }
    }

    private static void progressiveDisclosure(LearningRepository grouped, LearningRepository englishOnly,
                                              DictionaryRepository dictionary, Path output) throws Exception {
        LearningPanel panel = new LearningPanel("run", grouped, dictionary, ignored -> { });
        check(text(panel).contains("Original bilingual card 3"), "First three bilingual cards are visible");
        check(!text(panel).contains("Original bilingual card 4"), "Remaining bilingual cards start folded");
        check(text(panel).contains("原创") && text(panel).contains("真题"), "Original material is distinguished from exam questions");
        check(!text(panel).contains("We run, then RUN again."), "English dictionary examples start folded when bilingual examples exist");
        namedButton(panel, "examples-bilingual-more").doClick();
        check(text(panel).contains("Original bilingual card 5"), "Bilingual remainder expands");
        check(!text(panel).contains("We run, then RUN again."), "Expanding bilingual remainder does not expand English examples");
        namedButton(panel, "examples-english").doClick();
        check(text(panel).contains("We run, then RUN again."), "English examples can be expanded independently");
        check(!text(panel).contains("We are running.") && !text(panel).contains("A rerun begins."),
                "Inflection and substring-only sentences stay in the folded related section");
        check(highlightedText(area(panel, "We run, then RUN again.")).equals(List.of("run", "RUN")),
                "Word highlighting respects case and punctuation without highlighting substrings");
        namedButton(panel, "examples-related").doClick();
        check(text(panel).contains("We are running.") && text(panel).contains("A rerun begins."),
                "Related examples remain available rather than being deleted");
        for (int width : WIDTHS) render(panel, width, output.resolve("grouped-expanded-" + width + ".png"));
        namedButton(panel, "examples-bilingual-more").doClick();
        check(!text(panel).contains("Original bilingual card 4"), "Bilingual remainder collapses");
        check(text(panel).contains("We run, then RUN again.") && text(panel).contains("We are running."),
                "Collapsing bilingual remainder preserves both independent English section states");
        toggle(panel, "派生").doClick(); toggle(panel, "例句").doClick();
        check(!text(panel).contains("Original bilingual card 4") && text(panel).contains("We are running."),
                "Tab changes preserve independent disclosure states");
        LearningPanel only = new LearningPanel("run", englishOnly, dictionary, ignored -> { });
        check(text(only).contains("We run, then RUN again."), "Without bilingual material, matching English examples are visible");
        check(text(only).contains("原资料未提供中文译文"), "English-only examples never imply a translation exists");
        check(!text(only).contains("We are running."), "English-only view also folds other spellings");
        namedButton(only, "examples-related").doClick();
        for (int width : WIDTHS) render(only, width, output.resolve("english-only-expanded-" + width + ".png"));
    }

    private static void referencesAndNavigation(LearningRepository learning, DictionaryRepository dictionary) throws Exception {
        List<String> clicked = new ArrayList<>();
        LearningPanel panel = new LearningPanel("relevant", learning, dictionary, clicked::add);
        check(text(panel).contains("Her experience with databases"), "Existing original example remains visible");
        check(text(panel).contains("她使用数据库"), "Existing original translation remains visible");
        toggle(panel, "派生").doClick();
        button(panel, "irrelevant").doClick();
        check(clicked.equals(List.of("irrelevant")), "Related-word navigation reaches the existing callback");
        String sourceButton = "参考来源（" + learning.curated("relevant").stream().filter(i -> i.kind().equals("source")).count() + "）";
        button(panel, sourceButton).doClick();
        check(text(panel).contains("https://"), "References can be expanded without launching a browser");
        toggle(panel, "近义词").doClick();
        check(!text(panel).contains("https://"), "Opening derivation references does not open synonym references");
        button(panel, sourceButton).doClick();
        check(text(panel).contains("https://"), "Synonym references open independently");
        toggle(panel, "派生").doClick();
        check(text(panel).contains("https://"), "Returning to a tab remembers its own source state");
        button(panel, "收起参考来源").doClick();
        check(!text(panel).contains("https://"), "Closing one tab's sources works");
        toggle(panel, "近义词").doClick();
        check(text(panel).contains("https://"), "Closing derivation sources leaves synonym sources open");
        LearningPanel corpus = new LearningPanel("abandon", learning, dictionary, clicked::add);
        toggle(corpus, "词根").doClick();
        button(corpus, "开放资料出处").doClick();
        check(text(corpus).contains("https://"), "Open dictionary source addresses remain accessible");
        toggle(corpus, "派生").doClick();
        check(!text(corpus).contains("https://"), "Open source state is also isolated per tab");
        toggle(corpus, "词根").doClick();
        check(text(corpus).contains("https://"), "Open-source state survives a tab round trip");
        for (int width : WIDTHS) render(corpus, width, null);
        for (int width : new int[]{640, 340, 440}) {
            corpus.setSize(width, corpus.getHeight());
            layout(corpus);
            corpus.setSize(width, corpus.getPreferredSize().height);
            layout(corpus);
            assertTextBounds(corpus, width);
        }
    }

    private static void qualityAndSenses(LearningRepository quality, LearningRepository fallback,
                                         DictionaryRepository dictionary, Path output) throws Exception {
        LearningPanel panel = new LearningPanel("run", quality, dictionary, ignored -> { });
        check(text(panel).contains("推荐双语例句"), "Primary section describes the reading recommendations");
        check(sentenceCount(panel) == 3, "Only three unique bilingual sentences occupy the default view");
        check(text(panel).contains("We run every morning.") && !text(panel).contains("WE RUN, EVERY MORNING!"),
                "Case and punctuation variants occupy one default card");
        check(!text(panel).contains("many different neighborhoods") && !text(panel).contains("a run in the park"),
                "Long sentences and possible fragments start in More corpus");
        check(!text(panel).contains("They jog outside."), "Unrecorded word relationships are not guessed");
        check(!text(panel).contains("A period of running.") && !text(panel).contains("Move quickly on foot."),
                "Bilingual corpus is not silently assigned a WordNet sense");
        for (int width : WIDTHS) render(panel, width, output.resolve("quality-default-" + width + ".png"));
        button(panel, "出处与原译文（2 条记录）").doClick();
        check(text(panel).contains("我们每天早上跑步。") && text(panel).contains("我們每個早晨都跑步！"),
                "Duplicate grouping preserves both original translations verbatim");
        check(text(panel).contains("Source A") && text(panel).contains("Source B") && text(panel).contains("WE RUN, EVERY MORNING!"),
                "Duplicate source details retain both credits and original spelling");
        namedButton(panel, "examples-bilingual-more").doClick();
        check(text(panel).contains("many different neighborhoods") && text(panel).contains("长句"),
                "Long example remains available with the sorting reason");
        check(text(panel).contains("a run in the park") && text(panel).contains("句末标点"),
                "Fragment heuristic is explained when expanded");
        check(text(panel).contains("They jog outside.") && text(panel).contains("未直接出现查询词"),
                "Indirect material stays accessible and is labelled precisely");
        namedButton(panel, "examples-english").doClick();
        Container first = namedContainer(panel, "wordnet-sense-english-1");
        Container second = namedContainer(panel, "wordnet-sense-english-2");
        check(text(first).contains("名词 · 义项 1") && text(first).contains("A period of running."),
                "First source sense keeps its part of speech and definition");
        check(text(second).contains("动词 · 义项 2") && text(second).contains("Move quickly on foot."),
                "Second source sense is not collapsed into another sense");
        check(sentenceCount(first) == 2 && sentenceCount(second) == 1,
                "WordNet duplicate grouping stays within one real sense, never across senses");
        namedButton(panel, "examples-english-sense-0-source-1").doClick();
        check(text(panel).contains("WordNet credit A") && text(panel).contains("WordNet credit B"),
                "WordNet punctuation variants retain all source credits");
        namedButton(panel, "examples-selection").doClick();
        check(text(panel).contains("不判断语法、自然度或译文准确性"), "Selection does not claim automated semantic review");
        for (int width : WIDTHS) render(panel, width, output.resolve("quality-expanded-" + width + ".png"));

        LearningPanel limited = new LearningPanel("run", fallback, dictionary, ignored -> { });
        check(text(limited).contains("双语语料参考") && !text(limited).contains("推荐双语例句"),
                "Fallback does not mislabel low-signal material as a recommendation");
        check(text(limited).contains("Run!") && sentenceCount(limited) == 1,
                "A corpus with no eligible short sentences still exposes a labelled reference");
        namedButton(limited, "examples-bilingual-more").doClick();
        check(text(limited).contains("a run in the park"), "Fallback preserves the rest of the source material");
        for (int width : WIDTHS) render(limited, width, output.resolve("fallback-expanded-" + width + ".png"));
    }

    private static void bundledMaterial(LearningRepository learning, DictionaryRepository dictionary, Path output) throws Exception {
        LearningPanel missing = new LearningPanel("qznotawordqz", learning, dictionary, ignored -> { });
        for (String tab : TABS) {
            toggle(missing, tab).doClick();
            check(text(missing).contains("暂未收录"), "Explicit missing state: " + tab);
            for (int width : WIDTHS) render(missing, width, null);
        }
        LearningPanel research = new LearningPanel("research", learning, dictionary, ignored -> { });
        toggle(research, "近义词").doClick();
        check(text(research).contains("按词义分组"), "WordNet synonym senses remain grouped");
        for (int width : WIDTHS) render(research, width, null);
        for (String word : List.of("relevant", "achieve", "compare", "predict", "adequate", "sustainable",
                "hypothesis", "environment", "technology", "poverty", "organize")) {
            LearningPanel panel = new LearningPanel(word, learning, dictionary, ignored -> { });
            for (String tab : TABS) {
                toggle(panel, tab).doClick();
                String kind = switch (tab) {
                    case "例句" -> "example";
                    case "派生" -> "derived";
                    case "词根" -> "morphology";
                    default -> "synonym";
                };
                var item = learning.curated(word).stream().filter(i -> i.kind().equals(kind)).findFirst().orElseThrow();
                if (tab.equals("例句") && !text(panel).contains(item.first())) {
                    for (int width : WIDTHS) render(panel, width, null);
                    namedButton(panel, "examples-bilingual-more").doClick();
                }
                check(text(panel).contains(tab.equals("词根") ? item.second() : item.first()),
                        word + " existing Chinese material remains in " + tab);
                if (word.equals("relevant") && tab.equals("词根")) {
                    check(!text(panel).contains("同形词可能有不同来源"), "Raw historical record is folded initially");
                    namedButton(panel, "roots-records").doClick();
                    check(text(panel).contains("relevāns"), "Raw historical record remains available");
                }
                for (int width : WIDTHS) render(panel, width,
                        word.equals("sustainable") || word.equals("relevant")
                                ? output.resolve(word + "-" + tab + "-" + width + ".png") : null);
            }
        }
        for (String word : List.of("accumulate", "allocate", "abandon", "ability", "scheme", "happy")) {
            LearningPanel panel = new LearningPanel(word, learning, dictionary, ignored -> { });
            if (word.equals("allocate")) check(text(panel).contains("原创"), "Supplemental practice keeps its original-material label");
            if (word.equals("accumulate")) {
                if (!text(panel).contains("例句词形：")) namedButton(panel, "examples-bilingual-more").doClick();
                check(text(panel).contains("例句词形："), "Corpus inflection label is retained");
            }
            for (String tab : TABS) {
                toggle(panel, tab).doClick();
                if (word.equals("abandon") && tab.equals("词根")) check(text(panel).contains("中文词源资料"), "Chinese etymology remains visible");
                if (word.equals("ability") && tab.equals("词根")) check(text(panel).contains("构词成分的中文释义"), "Chinese affix gloss remains visible");
                for (int width : WIDTHS) render(panel, width,
                        word.equals("abandon") ? output.resolve("open-" + word + "-" + tab + "-" + width + ".png") : null);
                expandAll(panel);
                if (word.equals("allocate") && tab.equals("例句"))
                    check(text(panel).contains("词巢原创学习例句") && !text(panel).contains("拾词原创"),
                            "Bundled legacy attribution uses the current name in visible source details");
                for (int width : WIDTHS) render(panel, width, null);
            }
        }
    }

    /** Small fixture isolates case/word boundaries and independent disclosure behavior. */
    private static LearningRepository fixture(boolean bilingual) throws Exception {
        List<String[]> rows = List.of(
                new String[]{"run", "direct", "v", "A deliberately long dictionary definition kept with the source details.",
                        "We run, then RUN again.\tFixture dictionary source", "", ""},
                new String[]{"run", "related", "v", "A related lexical sense.",
                        "We are running.\tFixture dictionary source\nA rerun begins.\tFixture dictionary source", "", ""});
        StringBuilder curated = new StringBuilder();
        if (bilingual) for (int i = 1; i <= 5; i++) curated.append("example\trun\tOriginal bilingual card ")
                .append(i).append(" includes run.\t原创双语卡片 ").append(i).append("。\t原创学习例句；非考试真题\n");
        return fromRows(rows, curated.toString());
    }

    private static LearningRepository qualityFixture() throws Exception {
        List<String[]> rows = List.of(
                new String[]{"run", "source-sense-n", "n", "A period of running.",
                        "We run every morning.\tWordNet credit A\nWE RUN, EVERY MORNING!\tWordNet credit B\nThey run outside.\tWordNet credit C", "", ""},
                new String[]{"run", "source-sense-v", "v", "Move quickly on foot.",
                        "We run every morning.\tWordNet credit D", "", ""});
        String curated = "example\trun\tWe run through " + "many different neighborhoods ".repeat(10) + "before breakfast.\t长句原译文。\tLong source\n"
                + "example\trun\tWe run every morning.\t我们每天早上跑步。\tSource A\n"
                + "example\trun\tWE RUN, EVERY MORNING!\t我們每個早晨都跑步！\tSource B\n"
                + "example\trun\tThey run outside.\t他们在外面跑步。\tSource C\n"
                + "example\trun\tThey jog outside.\t他们在外面慢跑。\tIndirect source\n"
                + "example\trun\ta run in the park\t公园里的一次跑步\tFragment source\n"
                + "example\trun\tWe run after school.\t我们放学后跑步。\tSource D\n";
        return fromRows(rows, curated);
    }

    private static LearningRepository fromRows(List<String[]> rows, String curated) throws Exception {
        return LearningRepository.load(new ByteArrayInputStream(binary(0x574e4c31, rows)),
                new ByteArrayInputStream(binary(0x574e4531, List.of())),
                new ByteArrayInputStream(curated.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] binary(int magic, List<String[]> rows) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(bytes))) {
            data.writeInt(magic); data.writeInt(rows.size());
            for (String[] row : rows) for (String field : row) {
                byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                data.writeInt(encoded.length); data.write(encoded);
            }
        }
        return bytes.toByteArray();
    }

    private static List<String> highlightedText(JTextArea area) throws Exception {
        List<String> result = new ArrayList<>();
        for (Highlighter.Highlight highlight : area.getHighlighter().getHighlights())
            result.add(area.getDocument().getText(highlight.getStartOffset(), highlight.getEndOffset() - highlight.getStartOffset()));
        return result;
    }

    private static void expandAll(JPanel panel) {
        // Every action rebuilds the tree, so find a fresh button after every click.
        for (int pass = 0; pass < 100; pass++) {
            JButton next = components(panel).stream().filter(c -> c instanceof JButton).map(c -> (JButton)c)
                    .filter(b -> b.getText() != null && (b.getText().equals("出处") || b.getText().equals("开放资料出处")
                            || b.getText().equals("词典资料出处")
                            || b.getText().startsWith("更多语料（") || b.getText().startsWith("出处与原译文（")
                            || b.getText().startsWith("参考来源（") || b.getText().startsWith("展开其余 ")
                            || (b.getName() != null && (b.getName().equals("examples-english")
                            || b.getName().equals("examples-related") || b.getName().equals("roots-records")
                            || b.getName().equals("examples-collocations") || b.getName().equals("examples-selection")) && !b.getText().startsWith("收起"))))
                    .findFirst().orElse(null);
            if (next == null) return;
            next.doClick();
        }
        throw new AssertionError("Disclosure buttons did not settle after 100 actions");
    }

    private static void render(JPanel panel, int width, Path file) throws Exception {
        panel.setSize(width, 1000);
        for (int pass = 0; pass < 7; pass++) {
            invalidateTree(panel);
            panel.setSize(width, Math.max(100, panel.getPreferredSize().height)); layout(panel);
        }
        assertTextBounds(panel, width);
        int scale = 2;
        BufferedImage image = new BufferedImage(width * scale, panel.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.scale(scale, scale);
        graphics.setColor(Theme.WHITE); graphics.fillRect(0, 0, width, panel.getHeight());
        panel.printAll(graphics); graphics.dispose();
        assertRenderedTextWidths(panel, scale);
        if (file != null) ImageIO.write(image, "png", file.toFile());
    }

    /** Caret bounds alone miss integer font-advance changes on scaled Graphics2D contexts. */
    private static void assertRenderedTextWidths(JPanel panel, double scale) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.scale(scale, scale);
            for (Component component : components(panel)) if (component instanceof JTextArea area
                    && "example-english".equals(area.getName()) && !area.getText().isEmpty()) {
                var metrics = area.getFontMetrics(area.getFont()).getFontRenderContext();
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, metrics.getAntiAliasingHint());
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, metrics.getFractionalMetricsHint());
                String value = area.getText(); int start = 0;
                double y = area.modelToView2D(0).getY();
                for (int i = 1; i <= value.length(); i++) {
                    double nextY = area.modelToView2D(i).getY();
                    if (nextY > y || i == value.length()) {
                        String line = value.substring(start, i).stripTrailing();
                        double renderedWidth = area.getFont().getStringBounds(line, graphics.getFontRenderContext()).getWidth();
                        check(renderedWidth <= area.getWidth() + 1,
                                "Actual scaled glyph advances fit the visual line at " + scale + "x: " + line
                                + "; rendered=" + renderedWidth + "; available=" + area.getWidth());
                        start = i; y = nextY;
                    }
                }
            }
        } finally { graphics.dispose(); }
    }

    private static void assertTextBounds(JPanel panel, int width) throws Exception {
        check(panel.getHeight() < 18000, "Bounded content layout");
        for (Component component : components(panel)) if (component instanceof JTextArea area && !area.getText().isEmpty()) {
            String context = width + "px: " + area.getText().substring(0, Math.min(50, area.getText().length()));
            Rectangle2D last = area.modelToView2D(area.getDocument().getLength());
            check(last != null && last.getMaxY() <= area.getHeight() + 1, "Final text line is not clipped at " + context
                    + "; area=" + area.getBounds() + "; last=" + last + "; parent=" + area.getParent().getBounds()
                    + "; panel=" + panel.getBounds());
            check(last != null && last.getMaxX() <= area.getWidth() + 1, "Final text character is not clipped at " + context);
            Rectangle bounds = SwingUtilities.convertRectangle(area.getParent(), area.getBounds(), panel);
            check(bounds.x >= 0 && bounds.getMaxX() <= width + 1 && bounds.getMaxY() <= panel.getHeight() + 1,
                    "Text stays within the rendered panel at " + context);
        }
    }

    private static void invalidateTree(Container root) {
        for (Component child : root.getComponents()) if (child instanceof Container nested) invalidateTree(nested);
        root.invalidate();
    }
    private static void layout(Container root) { root.doLayout(); for (Component child : root.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static List<Component> components(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component component : root.getComponents()) {
            found.add(component); if (component instanceof Container nested) found.addAll(components(nested));
        }
        return found;
    }
    private static String text(Container root) {
        StringBuilder result = new StringBuilder();
        for (Component component : components(root)) {
            if (component instanceof JTextArea area) result.append(area.getText()).append('\n');
            if (component instanceof AbstractButton button) result.append(button.getText()).append('\n');
        }
        return result.toString();
    }
    private static JTextArea area(Container root, String exact) {
        return components(root).stream().filter(c -> c instanceof JTextArea a && exact.equals(a.getText()))
                .map(c -> (JTextArea)c).findFirst().orElseThrow(() -> new AssertionError("Missing text area: " + exact));
    }
    private static Container namedContainer(Container root, String name) {
        return components(root).stream().filter(c -> c instanceof Container && name.equals(c.getName()))
                .map(c -> (Container)c).findFirst().orElseThrow(() -> new AssertionError("Missing named container: " + name));
    }
    private static long sentenceCount(Container root) {
        return components(root).stream().filter(c -> c instanceof JTextArea && "example-english".equals(c.getName())).count();
    }
    private static JToggleButton toggle(Container root, String name) {
        return components(root).stream().filter(c -> c instanceof JToggleButton b && name.equals(b.getText()))
                .map(c -> (JToggleButton)c).findFirst().orElseThrow(() -> new AssertionError("Missing tab: " + name));
    }
    private static JButton button(Container root, String name) {
        return components(root).stream().filter(c -> c instanceof JButton b && name.equals(b.getText()))
                .map(c -> (JButton)c).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + name));
    }
    private static JButton namedButton(Container root, String name) {
        return components(root).stream().filter(c -> c instanceof JButton b && name.equals(b.getName()))
                .map(c -> (JButton)c).findFirst().orElseThrow(() -> new AssertionError("Missing named button: " + name));
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
