package wordnest;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Injected text source only: never reads or writes the user's clipboard. */
public final class ClipboardWatcherTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Headless only");
        acceptedAndRejected(); SwingUtilities.invokeAndWait(ClipboardWatcherTest::baselineAndChanges);
        check(Window.getWindows().length == 0, "No window or real clipboard is needed");
        System.out.println("PASS: " + assertions + " clipboard text and baseline assertions; fake source; no clipboard access");
    }
    private static void acceptedAndRejected() {
        for (String text : List.of("word", "Research", "took into account", "well-being", "can't", "can’t", "mother-in-law", " (take into account). ",
                "“take\u00a0into   account”", "one\ttwo", "one two three four five six seven eight"))
            check(ClipboardWatcher.isLookupText(text), "Accept bounded English words/phrases: " + text);
        for (String text : List.of("", " ", "中文", "word中文", "word\nother", "word\r", "word\u2028other", "https://example.com", "www.example.com",
                "a@example.com", "abc123", "12345678901234567890", "foo_bar", "x = y", "word()", "print(word)", "{ word }", "[word]", "<word>",
                "path/to/file", "C:\\word", "word; other", "one two three four five six seven eight nine", "x".repeat(121)))
            check(!ClipboardWatcher.isLookupText(text), "Reject paragraphs, addresses, code or unsupported input: " + text);
        check(!ClipboardWatcher.isLookupText(null), "Missing clipboard text is ignored");
        check(ClipboardWatcher.isSingleWord("  word\r\n") && !ClipboardWatcher.isSingleWord("two words") && !ClipboardWatcher.isSingleWord("can't"),
                "Legacy isSingleWord contract stays unchanged for existing callers/tests");
    }
    private static void baselineAndChanges() {
        AtomicReference<String> source = new AtomicReference<>("previous word"); AtomicInteger reads = new AtomicInteger(); List<String> found = new ArrayList<>();
        ClipboardWatcher watcher = new ClipboardWatcher(found::add, () -> { reads.incrementAndGet(); return source.get(); });
        try {
            watcher.setEnabled(true); watcher.poll();
            check(found.isEmpty(), "Enabling sets existing clipboard as baseline instead of querying it");
            source.set("“took into account”"); watcher.poll(); watcher.poll();
            check(found.equals(List.of("“took into account”")), "A changed valid phrase is emitted once with original punctuation preserved");
            source.set("https://example.com"); watcher.poll(); check(found.size() == 1, "Changed unsupported content does not trigger lookup");
            source.set(null); watcher.poll(); source.set("can't"); watcher.poll();
            check(found.get(1).equals("can't"), "Temporary clipboard lock does not disable subsequent valid changes");
            watcher.stop(); int count = reads.get(); source.set("paused word"); watcher.poll();
            check(reads.get() == count && found.size() == 2, "Stopped polling neither reads nor triggers a lookup");
            watcher.setEnabled(true); watcher.poll(); check(found.size() == 2, "Re-enabling resets the baseline");
        } finally { watcher.stop(); }
        source.set(null); found.clear();
        watcher = new ClipboardWatcher(found::add, source::get);
        try {
            watcher.setEnabled(true); source.set("existing after lock"); watcher.poll();
            check(found.isEmpty(), "If initial clipboard is locked, its first readable value becomes the baseline");
            source.set("new phrase"); watcher.poll(); check(found.equals(List.of("new phrase")), "Changes after a delayed baseline trigger normally");
        } finally { watcher.stop(); }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
