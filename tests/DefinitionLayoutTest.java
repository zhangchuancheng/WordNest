package wordnest;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.geom.Rectangle2D;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Measures actual final-character layout without showing or activating a window. */
public final class DefinitionLayoutTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "wordnest-layout-test-");
        Files.writeString(root.resolve("settings.properties"), "clipboard=false\npin=false\nlastWord=hello\n");
        MainWindow[] window = new MainWindow[1];
        List<String> clipping = new ArrayList<>();
        int[] measured = {0};
        try {
            onEdt(() -> window[0] = new MainWindow(root));
            long deadline = System.nanoTime() + 20_000_000_000L;
            boolean[] ready = {false};
            while (!ready[0] && System.nanoTime() < deadline) {
                onEdt(() -> ready[0] = (boolean) field(window[0], "loaded"));
                if (!ready[0]) Thread.sleep(50);
            }
            if (!ready[0]) throw new AssertionError("Dictionary failed to load");
            onEdt(() -> window[0].addNotify());
            for (int width : new int[]{440, 480, 640}) {
                for (String word : new String[]{"preserve", "hello", "serendipity", "apply", "get"}) {
                    onEdt(() -> {
                        window[0].setSize(width, 570);
                        Method lookup = MainWindow.class.getDeclaredMethod("lookup", String.class, boolean.class);
                        lookup.setAccessible(true); lookup.invoke(window[0], word, false);
                        window[0].validate();
                    });
                    // Let renderWord's deferred scroll update run before measuring.
                    for (int layoutWidth : width == 640 ? new int[]{640, 440} : new int[]{width}) onEdt(() -> {
                        window[0].setSize(layoutWidth, 570);
                        window[0].validate();
                        Container details = (Container) field(window[0], "details");
                        List<JTextArea> areas = new ArrayList<>();
                        collect(details, areas);
                        for (JTextArea area : areas) {
                            if (area.isEditable() || area.getText().isEmpty()) continue;
                            measured[0]++;
                            Rectangle2D end = area.modelToView2D(area.getDocument().getLength());
                            if (end == null) throw new AssertionError("No text layout for " + word);
                            double required = end.getMaxY() + area.getInsets().bottom;
                            if (required > area.getHeight() + 1) {
                                clipping.add(word + " @ " + width + "px rendered / " + layoutWidth + "px displayed: text width=" + area.getWidth()
                                        + ", assigned=" + area.getHeight() + ", preferred=" + area.getPreferredSize().height
                                        + ", last-character-bottom=" + required + ", rows=" + area.getRows());
                            }
                        }
                    });
                }
            }
            if (!clipping.isEmpty()) {
                for (String issue : clipping) System.out.println("CLIPPED " + issue);
                throw new AssertionError(clipping.size() + " of " + measured[0] + " text areas have clipped final lines");
            }
            System.out.println("PASS: " + measured[0] + " definition layout checks");
        } finally {
            if (window[0] != null) onEdt(() -> {
                ((ClipboardWatcher) field(window[0], "watcher")).stop();
                ((Timer) field(window[0], "saveNoteTimer")).stop();
                window[0].dispose();
            });
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void collect(Container container, List<JTextArea> result) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTextArea area) result.add(area);
            if (child instanceof Container nested) collect(nested, result);
        }
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void onEdt(CheckedAction action) throws Exception {
        Throwable[] failure = {null};
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Throwable cause) { failure[0] = cause; } });
        if (failure[0] instanceof Exception cause) throw cause;
        if (failure[0] instanceof Error cause) throw cause;
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
