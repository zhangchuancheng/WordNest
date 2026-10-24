package wordnest;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests actual dictionary detail components without constructing a Window or reading the clipboard. */
public final class WordDetailPanelTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Run with -Djava.awt.headless=true");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        DictionaryRepository dictionary = DictionaryTestResources.load();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            try {
                for (String word : List.of("coordinate", "relevant", "preserve", "apply", "get",
                        "internationalization", "pneumonoultramicroscopicsilicovolcanoconiosis")) {
                    WordEntry entry = dictionary.lookup(word).orElseThrow(() -> new AssertionError("Real dictionary fixture missing: " + word));
                    AtomicInteger favorites = new AtomicInteger();
                    JButton favorite = Theme.button("收藏", false);
                    favorite.addActionListener(e -> { favorites.incrementAndGet(); favorite.setText("已收藏"); });
                    WordDetailPanel panel = new WordDetailPanel(entry, favorite, null);
                    JButton english = (JButton) named(panel, "english-toggle");
                    Component content = named(panel, "english-content");
                    check(!effectivelyVisible(content, panel), word + ": English defaults to collapsed");
                    verifyChinese(entry, panel);
                    for (int width : new int[]{328, 408, 560}) {
                        render(panel, width, word.equals("coordinate") ? output.resolve("coordinate-collapsed-" + width + ".png") : null);
                    }
                    english.doClick();
                    check(effectivelyVisible(content, panel), word + ": English expands");
                    check(allText((Container) content).contains(entry.definition()), word + ": expansion retains complete English definition");
                    for (int width : new int[]{328, 408, 560}) render(panel, width,
                            word.equals("coordinate") && width == 408 ? output.resolve("coordinate-expanded-408.png") : null);
                    List<Component> beforeFavorite = components(panel);
                    favorite.doClick();
                    check(favorites.get() == 1, word + ": favorite invokes caller exactly once");
                    check(components(panel).equals(beforeFavorite), word + ": favorite does not rebuild detail controls");
                    check(effectivelyVisible(content, panel), word + ": favorite preserves expanded state");
                    render(panel, 328, null);
                    check(favorite.getWidth() >= favorite.getPreferredSize().width,
                            word + ": longer saved-state button label fits after favorite click");
                    english.doClick();
                    check(!effectivelyVisible(content, panel), word + ": English collapses again");
                    english.doClick();
                    check(allText((Container) content).contains(entry.definition()), word + ": repeated expansion preserves source data");
                    WordDetailPanel reopened = new WordDetailPanel(entry, Theme.button("已收藏", false), null);
                    check(!effectivelyVisible(named(reopened, "english-content"), reopened), word + ": new detail resets to collapsed");
                }
                verifyUnusualText(output);
                check(WordDetailPanel.displayPos("a.").equals("adj."), "Adjective abbreviation");
                check(WordDetailPanel.displayPos("ad.").equals("adv."), "Adverb abbreviation");
                check(WordDetailPanel.displayPos("adj. & adv.").equals("adj. & adv."), "Existing standard abbreviations unchanged");
                check(WordDetailPanel.displayPos("a. & ad.").equals("adj. & adv."), "Combined POS normalization");
                check(Window.getWindows().length == 0, "No Window was constructed");
                System.out.println("PASS: " + assertions + " headless word-detail assertions; no windows, clipboard or input injection");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static void verifyUnusualText(Path output) throws Exception {
        WordEntry punctuation = new WordEntry("edge-case", "/test/",
                "n. 保留括号（包括里面的说明）；保留分号\n[计算机] 原样的专业标记\n没有词性的一整行释义\nvt. 让较长的中文释义自动换行，并且最后的几个字仍然完整可见",
                "First English line.\nSecond English line; punctuation remains intact.", "cet4 ielts", "");
        WordDetailPanel panel = new WordDetailPanel(punctuation, Theme.button("收藏", false), Theme.button("查看原形", false));
        verifyChinese(punctuation, panel);
        render(panel, 328, output.resolve("long-meaning-328.png"));
        WordEntry empty = new WordEntry("empty-entry", "", "", "", "", "");
        render(new WordDetailPanel(empty, Theme.button("收藏", false), null), 328, null);
    }

    private static void verifyChinese(WordEntry entry, Container panel) {
        String actual = components(panel).stream()
                .filter(c -> c instanceof JTextArea && "meaning-text".equals(c.getName()))
                .map(c -> ((JTextArea)c).getText()).reduce("", (a, b) -> a + "\n" + b);
        for (String line : entry.translation().split("\\R")) {
            String meaning = line.strip().replaceFirst("(?i)^(?:adj|adv|aux|conj|interj|int|prep|pron|vt|vi|v|n|a|s|num|art|det|abbr|pl)\\.\\s*", "");
            if (!meaning.isBlank()) check(actual.contains(meaning), entry.word() + ": original Chinese meaning retained: " + meaning);
        }
    }

    private static void render(JPanel panel, int width, Path file) throws Exception {
        panel.setSize(width, 1000);
        for (int pass = 0; pass < 8; pass++) {
            invalidate(panel);
            layout(panel);
            panel.setSize(width, Math.max(1, panel.getPreferredSize().height));
        }
        layout(panel);
        check(panel.getHeight() > 0 && panel.getHeight() < 30000, "Bounded detail height at " + width);
        for (Component c : components(panel)) {
            if (!effectivelyVisible(c, panel)) continue;
            if (c instanceof JTextArea area && !area.getText().isEmpty()) {
                Rectangle2D last = area.modelToView2D(area.getDocument().getLength());
                check(last != null, "Text is laid out at " + width + " (" + area.getWidth() + "x" + area.getHeight() + "): " + shortText(area.getText()));
                check(last.getMaxY() + area.getInsets().bottom <= area.getHeight() + 1,
                        "Final text line is visible at " + width + ": " + shortText(area.getText()));
                check(area.getWidth() > 10, "Text receives horizontal space: " + shortText(area.getText()));
                for (int i = 0; i < area.getDocument().getLength(); i++) {
                    Rectangle2D character = area.modelToView2D(i);
                    check(character != null && character.getMaxX() <= area.getWidth() + 1,
                            "Character does not overflow at " + width + ": " + shortText(area.getText()));
                }
            } else if ("headword".equals(c.getName()) && c instanceof JLabel label) {
                check(label.getFontMetrics(label.getFont()).stringWidth(label.getText()) <= label.getWidth() + 1,
                        "Headword is not ellipsized at " + width);
            }
        }
        if (file != null) {
            BufferedImage image = new BufferedImage(width, panel.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Theme.PAPER); g.fillRect(0, 0, width, image.getHeight());
            panel.printAll(g); g.dispose();
            ImageIO.write(image, "png", file.toFile());
        }
    }

    private static String shortText(String text) { return text.substring(0, Math.min(50, text.length())).replace('\n', ' '); }
    private static boolean effectivelyVisible(Component c, Container boundary) {
        for (Component current = c; current != null && current != boundary; current = current.getParent()) {
            if (!current.isVisible()) return false;
        }
        return true;
    }
    private static Component named(Container root, String name) {
        return components(root).stream().filter(c -> name.equals(c.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing named control " + name));
    }
    private static String allText(Container root) {
        StringBuilder out = new StringBuilder();
        if (root instanceof JTextArea area) out.append(area.getText());
        for (Component c : components(root)) if (c instanceof JTextArea area) out.append(area.getText()).append('\n');
        return out.toString();
    }
    private static List<Component> components(Container root) {
        List<Component> found = new ArrayList<>();
        for (Component c : root.getComponents()) { found.add(c); if (c instanceof Container nested) found.addAll(components(nested)); }
        return found;
    }
    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static void invalidate(Container root) {
        root.invalidate();
        for (Component child : root.getComponents()) if (child instanceof Container nested) invalidate(nested);
    }
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
}
