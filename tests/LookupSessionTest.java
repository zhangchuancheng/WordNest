package wordnest;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises the production note/navigation coordinator and real JPanel controls without any Window. */
public final class LookupSessionTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        check(GraphicsEnvironment.isHeadless(), "Use headless mode");
        Path root = Files.createTempDirectory("wordnest-navigation-");
        try {
            testNavigation(root);
            SwingUtilities.invokeAndWait(LookupSessionTest::testBar);
            check(Window.getWindows().length == 0, "No window was created");
            System.out.println("PASS: " + assertions + " lookup session and navigation JPanel assertions (no windows)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void testNavigation(Path root) throws Exception {
        LookupHistory history = new LookupHistory(root);
        WordEntry alpha = entry("alpha"), beta = entry("beta"), gamma = entry("gamma");
        Map<String, WordEntry> words = Map.of("alpha", alpha, "beta", beta, "gamma", gamma);
        AtomicBoolean canSave = new AtomicBoolean(true);
        List<String> displayed = new ArrayList<>();
        LookupSession session = new LookupSession(history, value -> Optional.ofNullable(words.get(value)),
                canSave::get, entry -> displayed.add(entry.word()));
        check(session.visit(alpha, false).opened(), "Startup displays the restored word");
        check(history.recent().isEmpty(), "Startup is not a fresh history visit");
        check(!history.canBack(), "Startup creates only the session origin");
        canSave.set(false);
        check(!session.visit(beta, true).opened(), "Failed note save blocks a normal lookup");
        check(displayed.equals(List.of("alpha")), "Failed save keeps the visible word");
        check(history.recent().isEmpty() && !history.canBack(), "Failed save does not advance history");
        canSave.set(true);
        check(session.visit(beta, true).opened(), "Saved note permits a new lookup");
        check(history.peekBack().orElseThrow().equals("alpha"), "The previous word is navigable");
        check(session.redisplayCurrent().opened(), "Return from missing-result page can redisplay the last success");
        check(displayed.get(displayed.size() - 1).equals("beta"), "Missing-result recovery does not skip the last word");
        check(history.peekBack().orElseThrow().equals("alpha") && !history.canForward(), "Redisplay preserves the navigation cursor");
        canSave.set(false);
        check(!session.back().opened(), "Failed note save blocks back navigation");
        check(history.peekBack().orElseThrow().equals("alpha") && !history.canForward(), "Blocked back leaves cursor unchanged");
        canSave.set(true);
        check(session.back().opened(), "Back succeeds after saving recovers");
        check(displayed.get(displayed.size() - 1).equals("alpha"), "Back displays the right word");
        check(history.recent().equals(List.of("beta")), "Back does not rewrite recent queries");
        canSave.set(false);
        check(!session.forward().opened(), "Failed save blocks forward too");
        canSave.set(true);
        check(session.forward().opened(), "Forward recovers after saving");
        check(session.back().opened(), "Can return to the branch point");
        check(session.visit(gamma, true).opened() && !history.canForward(), "New query after back replaces forward branch");

        Path file = root.resolve("history.tsv");
        byte[] oldHistory = Files.readAllBytes(file);
        Files.delete(file); Files.createDirectory(file);
        LookupSession.Result degraded = session.visit(beta, true);
        check(degraded.opened() && !degraded.warning().isEmpty(), "History disk failure does not block a dictionary result");
        check(displayed.get(displayed.size() - 1).equals("beta"), "Disk failure still displays the requested word");
        check(history.peekBack().orElseThrow().equals("gamma"), "Fallback session matches the visible page");
        check(history.recent().equals(List.of("gamma", "beta")), "Disk failure leaves persistent-recent snapshot untouched");
        check(session.back().opened(), "Session back works while history storage is unavailable");
        Files.delete(file); Files.write(file, oldHistory);
        check(session.visit(alpha, true).warning().isEmpty(), "Later persistence can recover");
        check(new LookupHistory(root).recent().get(0).equals("alpha"), "Recovered record survives restart");

        LookupSession missingDictionary = new LookupSession(history, ignored -> Optional.empty(),
                canSave::get, ignored -> { throw new AssertionError("Missing word must not display"); });
        Optional<String> target = history.peekBack();
        check(!missingDictionary.back().opened(), "Missing historical dictionary entry is rejected");
        check(history.peekBack().equals(target), "Missing entry does not move history cursor");
        history.clear(); history.seedSession(alpha.word());
        check(!history.canBack() && !history.canForward() && history.recent().isEmpty(), "Clear keeps only explicit in-memory current origin");
    }

    private static void testBar() {
        Theme.install();
        List<String> events = new ArrayList<>();
        LookupNavigationBar bar = new LookupNavigationBar(() -> events.add("back"),
                () -> events.add("forward"), () -> events.add("recent"));
        JButton back = (JButton) bar.getComponent(0), forward = (JButton) bar.getComponent(1), recent = (JButton) bar.getComponent(2);
        check(!back.isEnabled() && !forward.isEnabled() && !recent.isEnabled(), "Empty navigation has no actionable buttons");
        bar.update(true, false, 100);
        back.doClick(); forward.doClick(); recent.doClick();
        check(events.equals(List.of("back", "recent")), "Only enabled controls dispatch their own actions");
        bar.update(false, true, 7); forward.doClick();
        check(events.equals(List.of("back", "recent", "forward")), "Forward callback is correctly wired");
        check(recent.getText().contains("7"), "Recent count updates");
        for (int width : new int[]{340, 440, 640}) {
            bar.update(true, true, 100);
            bar.setSize(width, bar.getPreferredSize().height); bar.doLayout();
            for (Component child : bar.getComponents()) {
                check(child.getX() >= 0 && child.getX() + child.getWidth() <= width, "Buttons fit horizontal bounds at " + width);
                check(child.getY() >= 0 && child.getY() + child.getHeight() <= bar.getHeight(), "Buttons fit vertical bounds at " + width);
            }
            BufferedImage image = new BufferedImage(width, bar.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try { bar.printAll(graphics); } finally { graphics.dispose(); }
        }
    }

    private static WordEntry entry(String word) { return new WordEntry(word, "", "测试释义", "Test definition", "", ""); }
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
}
