package wordnest;

import javax.swing.Timer;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.util.function.Consumer;

/** Polls for changed text only. Does not listen to keys, transmit text, or request focus. */
final class ClipboardWatcher {
    private final Timer timer;
    private final Consumer<String> onWord;
    private String previous;
    ClipboardWatcher(Consumer<String> onWord) {
        this.onWord = onWord;
        timer = new Timer(650, event -> poll());
        timer.setCoalesce(true);
    }
    static boolean isSingleWord(String text) {
        return text != null && text.length() <= 80 && text.strip().matches("[A-Za-z]+");
    }
    void setEnabled(boolean enabled) {
        timer.stop();
        // Treat existing clipboard text as a baseline, not a new user lookup.
        previous = readText();
        if (enabled) timer.start();
    }
    void stop() { timer.stop(); }
    private void poll() {
        String value = readText();
        if (value == null) return;
        if (!value.equals(previous)) {
            previous = value;
            if (isSingleWord(value)) onWord.accept(value.strip());
        }
    }
    private String readText() {
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return "";
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            if (!(data instanceof String value)) return "";
            return value.length() <= 80 ? value : "<non-word-content>";
        } catch (Exception ignored) {
            // Other applications can temporarily lock the Windows clipboard.
            return null;
        }
    }
}
