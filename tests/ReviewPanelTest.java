package wordnest;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Real JPanel tests only: no JFrame/JDialog, peers, clipboard, tray, or desktop input. */
public final class ReviewPanelTest {
    private static int assertions;
    private static Path previewDirectory;

    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Run with -Djava.awt.headless=true");
        if (args.length > 0) previewDirectory = Files.createDirectories(Path.of(args[0]));
        Path root = Files.createTempDirectory("wordnest-review-panel-");
        try {
            onEdt(Theme::install);
            onEdt(() -> finiteRound(root.resolve("finite")));
            onEdt(() -> resumeAndCancel(root.resolve("resume")));
            onEdt(() -> failedAnswer(root.resolve("failure")));
            onEdt(() -> emptyFavorites(root.resolve("empty")));
            onEdt(() -> longContent(root.resolve("long")));
            if (previewDirectory != null) {
                DictionaryRepository dictionary = DictionaryRepository.load(ReviewPanelTest.class.getResourceAsStream("/dictionary.bin.gz"));
                onEdt(() -> realWordPreviews(root.resolve("preview"), dictionary));
            }
            check(Window.getWindows().length == 0, "tests did not construct any Window");
            System.out.println("PASS: " + assertions + " review JPanel assertions (headless, no Window)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void finiteRound(Path root) throws Exception {
        ReviewStoreTest.MutableClock clock = clock();
        ReviewStore store = new ReviewStore(root, clock);
        List<SavedWord> words = ReviewStoreTest.words(3);
        List<String> spoken = new ArrayList<>();
        int[] stopped = {0};
        ReviewPanel panel = new ReviewPanel(store, () -> words, () -> {}, spoken::add, () -> stopped[0]++);
        check(button(panel, "review-start").getText().equals("开始复习"), "new panel offers start");
        check(((JComboBox<?>) find(panel, "review-size")).getSelectedIndex() == 0, "round defaults to ten");
        check(text(panel, "review-summary").contains("未学 3"), "summary reflects actual current favorites");
        click(panel, "review-start");
        preview(panel, "question");
        check(text(panel, "review-word").equals("word0"), "first question is shown");
        check(!button(panel, "review-forgot").isEnabled() && !button(panel, "review-remembered").isEnabled(), "grading is disabled before reveal");
        check(!text(panel, "review-definition").contains("中文 0"), "meaning is hidden before recall");
        click(panel, "review-remembered");
        check(store.snapshot(words).round().orElseThrow().position() == 0, "disabled grade cannot move the round");
        click(panel, "review-speak");
        check(spoken.equals(List.of("word0")), "optional pronunciation receives just the current headword");
        click(panel, "review-reveal");
        check(stopped[0] == 0, "revealing the same question does not stop its pronunciation");
        preview(panel, "answer");
        check(text(panel, "review-definition").contains("中文 0") && text(panel, "review-definition").contains("例句 0"), "reveal shows meaning and personal context");
        check(button(panel, "review-forgot").isEnabled() && button(panel, "review-remembered").isEnabled(), "both honest grading choices become available");
        click(panel, "review-forgot");
        check(stopped[0] == 1, "successfully moving to another question stops prior pronunciation");
        check(text(panel, "review-word").equals("word1") && !button(panel, "review-remembered").isEnabled(), "successful answer advances and hides the next meaning");
        check(store.progress("word0").orElseThrow().dueAt() == clock.millis() + 600_000, "forgotten question is scheduled after this finite round");
        click(panel, "review-reveal"); click(panel, "review-remembered");
        click(panel, "review-reveal"); click(panel, "review-remembered");
        check(text(panel, "review-round-status").contains("本轮完成")
                && text(panel, "review-round-status").contains("记住 2 词 · 忘记 1 词"), "round stops with exact remembered and forgotten totals");
        check(!button(panel, "review-start").isEnabled(), "no due work cannot immediately start another loop");
        check(stopped[0] == 3, "round completion also stops the last question pronunciation");
        preview(panel, "completed");
        check(text(panel, "review-plan").contains("目前没有到期") && text(panel, "review-summary").contains("下次计划时间"), "completion gives no-due explanation and next time");
        for (int width : new int[]{340, 460, 680}) paint(panel, width, 520);
        clock.millis += 600_000;
        click(panel, "review-refresh");
        check(button(panel, "review-start").isEnabled() && text(panel, "review-summary").contains("到期 1"), "refresh rechecks the injected due time");
        click(panel, "review-start");
        check(text(panel, "review-word").equals("word0"), "forgotten word is offered when its next time arrives");
    }

    private static void resumeAndCancel(Path root) throws Exception {
        ReviewStoreTest.MutableClock clock = clock();
        List<SavedWord> words = new ArrayList<>(ReviewStoreTest.words(25));
        ReviewStore store = new ReviewStore(root, clock);
        ReviewPanel panel = new ReviewPanel(store, () -> words, () -> {});
        ((JComboBox<?>) find(panel, "review-size")).setSelectedIndex(1);
        click(panel, "review-start");
        check(store.snapshot(words).round().orElseThrow().words().size() == 20, "twenty selector reaches the actual store");
        click(panel, "review-reveal"); click(panel, "review-remembered");
        ReviewStore restored = new ReviewStore(root, clock);
        ReviewPanel reopened = new ReviewPanel(restored, () -> words, () -> {});
        check(button(reopened, "review-start").getText().equals("继续本轮"), "reopening offers continuation");
        check(!find(reopened, "review-size").isEnabled(), "unfinished round cannot silently change size");
        check(((JComboBox<?>) find(reopened, "review-size")).getSelectedIndex() == 1, "resumed twenty-word round is not labeled as ten");
        click(reopened, "review-start");
        check(text(reopened, "review-word").equals("word1"), "continue resumes the first unanswered word");
        check(!button(reopened, "review-remembered").isEnabled(), "reopened question starts with meaning hidden");
        words.removeIf(item -> item.entry().word().equals("word1"));
        reopened.refresh();
        check(text(reopened, "review-word").equals("word2"), "refresh skips a canceled current favorite");
        check(restored.snapshot(words).round().orElseThrow().skipped() == 1 && restored.progress("word1").isEmpty(), "skip is saved without grading canceled word");
        // A cancellation between displaying the question and grading must not answer its successor.
        click(reopened, "review-reveal");
        words.removeIf(item -> item.entry().word().equals("word2"));
        click(reopened, "review-remembered");
        check(text(reopened, "review-word").equals("word3") && restored.progress("word3").isEmpty(), "stale grade after cancellation does not score the next word");
        words.clear(); reopened.refresh();
        check(text(reopened, "review-round-status").contains("本轮完成"), "canceling remaining queue completes the round");
        check(restored.snapshot(words).round().orElseThrow().remembered() == 1
                && restored.snapshot(words).round().orElseThrow().skipped() == 19, "completion separates skips from actual remembered answers");
    }

    private static void failedAnswer(Path root) throws Exception {
        ReviewStoreTest.MutableClock clock = clock();
        List<SavedWord> words = new ArrayList<>(ReviewStoreTest.words(2));
        ReviewStore store = new ReviewStore(root, clock);
        ReviewPanel panel = new ReviewPanel(store, () -> words, () -> {});
        click(panel, "review-start"); click(panel, "review-reveal");
        Path file = root.resolve("review.tsv"), original = root.resolve("original.tsv");
        block(file, original);
        try {
            click(panel, "review-remembered");
            check(text(panel, "review-message").contains("保存失败"), "save failure has visible feedback");
            check(text(panel, "review-word").equals("word0") && text(panel, "review-definition").contains("中文 0"), "failure preserves the same revealed question");
            check(button(panel, "review-remembered").isEnabled() && button(panel, "review-forgot").isEnabled(), "both choices remain usable for retry");
            check(store.snapshot(words).round().orElseThrow().position() == 0 && store.progress("word0").isEmpty(), "failed UI grading does not change schedule or position");
        } finally { unblock(file, original); }
        click(panel, "review-remembered");
        check(text(panel, "review-word").equals("word1") && new ReviewStore(root, clock).snapshot(words).round().orElseThrow().position() == 1, "retry advances and persists once");
        block(file, original);
        try {
            words.clear(); panel.refresh();
            check(find(panel, "review-retry") instanceof JButton && text(panel, "review-canceled").contains("取消收藏"), "failed skip exposes an explicit retry without grading a removed word");
            check(store.snapshot(words).round().orElseThrow().position() == 1, "failed skip retains stored position");
        } finally { unblock(file, original); }
        click(panel, "review-retry");
        check(text(panel, "review-round-status").contains("本轮完成"), "skip retry completes the round after storage recovers");
    }

    private static void emptyFavorites(Path root) throws Exception {
        int[] opened = {0};
        ReviewPanel panel = new ReviewPanel(new ReviewStore(root, clock()), List::of, () -> opened[0]++);
        check(!button(panel, "review-start").isEnabled(), "empty favorites cannot start");
        check(text(panel, "review-plan").contains("还没有收藏词"), "empty state explains where words come from");
        click(panel, "review-library"); check(opened[0] == 1, "empty state can return to the library");
        check(!Files.exists(root.resolve("review.tsv")), "empty panel creates no progress file");
    }

    private static void longContent(Path root) throws Exception {
        String meaning = "这是一条需要换行显示的释义，保留全部内容。".repeat(100);
        List<SavedWord> words = List.of(new SavedWord(new WordEntry("a moderately long headword", "phonetic", meaning, "", "", ""),
                "This is a long personal example that must remain scrollable. ".repeat(150), 1));
        ReviewPanel panel = new ReviewPanel(new ReviewStore(root, clock()), () -> words, () -> {});
        click(panel, "review-start"); click(panel, "review-reveal");
        panel.setSize(340, 520); layout(panel); layout(panel); layout(panel);
        JTextArea definition = (JTextArea) find(panel, "review-definition");
        check(definition.getHeight() >= definition.getPreferredSize().height,
                "long revealed meaning and note receive their full wrapped height");
        for (String name : new String[]{"review-reveal", "review-forgot", "review-remembered"}) {
            Component button = find(panel, name);
            Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), panel);
            check(bounds.x >= 0 && bounds.x + bounds.width <= panel.getWidth(), "narrow review keeps " + name + " inside horizontal bounds");
            check(bounds.y >= 0 && bounds.y + bounds.height <= panel.getHeight(), "long notes keep " + name + " visible without scrolling");
        }
        check(text(panel, "review-definition").contains(meaning), "long definitions are never truncated");
    }

    private static void realWordPreviews(Path root, DictionaryRepository dictionary) throws Exception {
        String note = "Keep only the evidence relevant to the research question.\n只保留与研究问题相关的证据。\n\n"
                + "阅读笔记（预览示例）：relevant to 表示与某个主题有关。记录一个完整语境，回看时再判断信息是否切题。\n".repeat(15);
        List<SavedWord> words = List.of(new SavedWord(dictionary.lookup("relevant").orElseThrow(), note, 1),
                new SavedWord(dictionary.lookup("certificate").orElseThrow(), "Where's my birth certificate?", 1));
        ReviewStore store = new ReviewStore(root, clock());
        ReviewPanel panel = new ReviewPanel(store, () -> words, () -> {}, ignored -> {}, () -> {});
        preview(panel, "relevant-plan");
        click(panel, "review-start"); preview(panel, "relevant-question");
        click(panel, "review-reveal");
        preview(panel, "relevant-long-answer");
        preview(panel, "relevant-long-answer-bottom", true);
        JTextArea area = (JTextArea) find(panel, "review-definition");
        check(area.getText().endsWith(note), "real dictionary preview preserves its full temporary personal note");
        JScrollPane scroll = scroll(panel);
        check(scroll.getVerticalScrollBar().getMaximum() > scroll.getVerticalScrollBar().getVisibleAmount(),
                "long real-word note has a scrollable full-height view");
        click(panel, "review-remembered"); click(panel, "review-reveal"); click(panel, "review-forgot");
        preview(panel, "relevant-completed");
    }

    private static void block(Path file, Path original) throws Exception {
        Files.move(file, original); Files.createDirectory(file); Files.writeString(file.resolve("blocker"), "blocked");
    }
    private static void unblock(Path file, Path original) throws Exception {
        Files.delete(file.resolve("blocker")); Files.delete(file); Files.move(original, file);
    }
    private static ReviewStoreTest.MutableClock clock() { return new ReviewStoreTest.MutableClock(Instant.parse("2026-10-23T08:00:00Z").toEpochMilli()); }
    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) {
                Component found = findOrNull(container, name); if (found != null) return found;
            }
        }
        throw new AssertionError("No component: " + name);
    }
    private static Component findOrNull(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) { Component found = findOrNull(container, name); if (found != null) return found; }
        }
        return null;
    }
    private static JButton button(Container parent, String name) { return (JButton) find(parent, name); }
    private static String text(Container parent, String name) { return ((JTextArea) find(parent, name)).getText(); }
    private static void click(Container parent, String name) { button(parent, name).doClick(0); }
    private static void paint(JPanel panel, int width, int height) {
        panel.setSize(width, height); layout(panel);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
        check(panel.getWidth() == width && panel.getHeight() == height, "pure panel paints at " + width + "px");
    }
    private static void preview(JPanel panel, String name) throws Exception {
        preview(panel, name, false);
    }
    private static void preview(JPanel panel, String name, boolean bottom) throws Exception {
        if (previewDirectory == null) return;
        for (int width : new int[]{340, 460}) {
            panel.setSize(width, 520); layout(panel); layout(panel); layout(panel);
            JScrollBar vertical = scroll(panel).getVerticalScrollBar(); vertical.setValue(bottom ? vertical.getMaximum() : 0);
            layout(panel);
            BufferedImage image = new BufferedImage(width * 2, 1040, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); graphics.scale(2, 2);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(Theme.PAPER); graphics.fillRect(0, 0, width, 520);
            panel.printAll(graphics); graphics.dispose();
            javax.imageio.ImageIO.write(image, "png", previewDirectory.resolve(name + "-" + width + ".png").toFile());
        }
    }
    private static JScrollPane scroll(Container parent) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JScrollPane pane) return pane;
            if (child instanceof Container nested) {
                JScrollPane found = scroll(nested); if (found != null) return found;
            }
        }
        return null;
    }
    private static void layout(Container container) { container.doLayout(); for (Component child : container.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
    private static void onEdt(Action action) throws Exception {
        Throwable[] failure = {null};
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Throwable ex) { failure[0] = ex; } });
        if (failure[0] instanceof Exception ex) throw ex;
        if (failure[0] instanceof Error ex) throw ex;
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
