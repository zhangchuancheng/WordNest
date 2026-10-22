package wordnest;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Pure JPanel tests and offscreen previews. No window, clipboard or input injection. */
public final class LearningPanelTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Run with -Djava.awt.headless=true");
        Path output = Path.of(args[0]); Files.createDirectories(output);
        DictionaryRepository dictionary = DictionaryRepository.load(App.class.getResourceAsStream("/dictionary.bin.gz"));
        LearningRepository learning = LearningRepository.load();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            try {
                List<String> clicked = new ArrayList<>();
                LearningPanel panel = new LearningPanel("relevant", learning, dictionary, clicked::add);
                check(text(panel).contains("Her experience with databases"), "Original example is visible");
                check(text(panel).contains("她使用数据库"), "Chinese translation is visible");
                check(text(panel).contains("原资料未提供中文译文"), "English-only source is labelled");
                for (String tab : List.of("例句", "派生", "词根", "近义词")) {
                    toggle(panel, tab).doClick();
                    for (int width : new int[]{340, 440}) render(panel, width, output.resolve("relevant-" + tab + "-" + width + ".png"));
                    String contents = text(panel);
                    switch (tab) {
                        case "派生" -> { check(contents.contains("relevance"), "Derived noun"); check(contents.contains("复数、过去式"), "Inflection distinction"); }
                        case "词根" -> { check(contents.contains("re- / lev / -ant"), "Learning decomposition"); check(contents.contains("历史"), "History distinction"); check(contents.contains("relevāns"), "Actual etymology data"); }
                        case "近义词" -> { check(contents.contains("pertinent"), "Sense-aware synonym"); check(contents.contains("互换"), "Context caution"); }
                        default -> { }
                    }
                }
                toggle(panel, "派生").doClick();
                button(panel, "irrelevant").doClick();
                check(clicked.equals(List.of("irrelevant")), "Related-word navigation reaches callback");
                button(panel, "参考来源（" + learning.curated("relevant").stream().filter(i -> i.kind().equals("source")).count() + "）").doClick();
                check(text(panel).contains("https://"), "References can be expanded without launching browser");
                LearningPanel missing = new LearningPanel("qznotawordqz", learning, dictionary, clicked::add);
                for (String tab : List.of("例句", "派生", "词根", "近义词")) {
                    toggle(missing, tab).doClick();
                    check(text(missing).contains("暂未收录"), "Explicit missing state: " + tab);
                    render(missing, 340, null);
                }
                LearningPanel research = new LearningPanel("research", learning, dictionary, clicked::add);
                toggle(research, "近义词").doClick();
                check(text(research).contains("按词义分组"), "WordNet senses grouped"); render(research, 340, null);
                for (String word : List.of("achieve", "compare", "predict", "adequate", "sustainable",
                        "hypothesis", "environment", "technology", "poverty", "organize")) {
                    LearningPanel expanded = new LearningPanel(word, learning, dictionary, clicked::add);
                    for (String tab : List.of("例句", "派生", "词根", "近义词")) {
                        toggle(expanded, tab).doClick();
                        String kind = switch (tab) {
                            case "例句" -> "example";
                            case "派生" -> "derived";
                            case "词根" -> "morphology";
                            default -> "synonym";
                        };
                        var item = learning.curated(word).stream().filter(i -> i.kind().equals(kind)).findFirst().orElseThrow();
                        String expected = tab.equals("词根") ? item.second() : item.first();
                        check(text(expanded).contains(expected), word + " new Chinese material shown in " + tab);
                        render(expanded, 340, word.equals("sustainable") ? output.resolve(word + "-" + tab + ".png") : null);
                    }
                }
                for (String word : List.of("accumulate", "allocate", "abandon", "ability", "scheme", "happy")) {
                    LearningPanel corpus = new LearningPanel(word, learning, dictionary, clicked::add);
                    if (word.equals("allocate")) check(text(corpus).contains("拾词原创学习例句"), "new original has honest label");
                    if (word.equals("accumulate")) {
                        check(text(corpus).contains("例句词形："), "inflected corpus example labelled");
                        button(corpus, "开放资料出处").doClick();
                        check(text(corpus).contains("https://tatoeba.org/en/sentences/show/"), "corpus IDs accessible offline");
                        button(corpus, "收起开放资料出处").doClick();
                    }
                    for (String tab : List.of("例句", "派生", "词根", "近义词")) {
                        toggle(corpus, tab).doClick();
                        if (word.equals("abandon") && tab.equals("词根")) check(text(corpus).contains("中文词源资料"), "Chinese etymology rendered");
                        if (word.equals("ability") && tab.equals("词根")) check(text(corpus).contains("构词成分的中文释义"), "sourced affix gloss rendered");
                        for (int width : new int[]{340, 440}) render(corpus, width,
                                word.equals("abandon") && width == 340 ? output.resolve("open-" + word + "-" + tab + ".png") : null);
                    }
                }
                System.out.println("PASS: " + assertions + " headless learning UI assertions; no windows created");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private static void render(JPanel panel, int width, Path file) throws Exception {
        panel.setSize(width, 1000);
        for (int pass = 0; pass < 5; pass++) {
            panel.setSize(width, Math.max(100, panel.getPreferredSize().height)); layout(panel);
        }
        check(panel.getHeight() < 18000, "Bounded content layout");
        for (Component component : components(panel)) if (component instanceof JTextArea area && !area.getText().isEmpty()) {
            Rectangle2D last = area.modelToView2D(area.getDocument().getLength());
            check(last != null && last.getMaxY() <= area.getHeight() + 1,
                    "No clipped text at " + width + "px: " + area.getText().substring(0, Math.min(40, area.getText().length())));
        }
        BufferedImage image = new BufferedImage(width, panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.setColor(Theme.WHITE); graphics.fillRect(0, 0, width, image.getHeight());
        panel.printAll(graphics); graphics.dispose();
        if (file != null) ImageIO.write(image, "png", file.toFile());
    }
    private static void layout(Container c) { c.doLayout(); for (Component child : c.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static List<Component> components(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component c : root.getComponents()) { found.add(c); if (c instanceof Container nested) found.addAll(components(nested)); }
        return found;
    }
    private static String text(Container root) {
        StringBuilder text = new StringBuilder();
        for (Component c : components(root)) {
            if (c instanceof JTextArea a) text.append(a.getText()).append('\n');
            if (c instanceof AbstractButton b) text.append(b.getText()).append('\n');
        }
        return text.toString();
    }
    private static JToggleButton toggle(Container root, String name) {
        return components(root).stream().filter(c -> c instanceof JToggleButton b && name.equals(b.getText())).map(c -> (JToggleButton)c).findFirst().orElseThrow();
    }
    private static JButton button(Container root, String name) {
        return components(root).stream().filter(c -> c instanceof JButton b && name.equals(b.getText())).map(c -> (JButton)c).findFirst().orElseThrow();
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
