package wordnest;

import javax.swing.Timer;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Polls for changed text only. Does not listen to keys, transmit text, or request focus. */
final class ClipboardWatcher {
    private final Timer timer;
    private final Consumer<String> onWord;
    private final Supplier<String> textSource;
    private String previous;
    private boolean hasBaseline;
    ClipboardWatcher(Consumer<String> onWord) {
        this(onWord, ClipboardWatcher::readSystemText);
    }
    /** Injected source makes baseline and change handling testable without accessing a real clipboard. */
    ClipboardWatcher(Consumer<String> onWord, Supplier<String> textSource) {
        this.onWord = onWord;
        this.textSource = textSource;
        timer = new Timer(650, event -> poll());
        timer.setCoalesce(true);
    }
    static boolean isSingleWord(String text) {
        return text != null && text.length() <= 80 && text.strip().matches("[A-Za-z]+");
    }
    static boolean isLookupText(String text) { return LookupResolver.isLookupText(text); }
    void setEnabled(boolean enabled) {
        timer.stop();
        // Treat existing clipboard text as a baseline, not a new user lookup.
        previous = textSource.get();
        hasBaseline = previous != null;
        if (enabled) timer.start();
    }
    void stop() { timer.stop(); }
    void poll() {
        if (!timer.isRunning()) return;
        String value = textSource.get();
        if (value == null) return;
        if (!hasBaseline) { previous = value; hasBaseline = true; return; }
        if (!value.equals(previous)) {
            previous = value;
            if (isLookupText(value)) onWord.accept(value.strip());
        }
    }
    private static String readSystemText() {
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return "";
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            if (!(data instanceof String value)) return "";
            return value.length() <= LookupResolver.MAX_CHARACTERS ? value : "<non-word-content>";
        } catch (Exception ignored) {
            // Other applications can temporarily lock the Windows clipboard.
            return null;
        }
    }
}
