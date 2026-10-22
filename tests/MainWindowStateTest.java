package wordnest;

import javax.swing.JButton;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Hidden-window state regression tests; does not activate windows or change the clipboard. */
public final class MainWindowStateTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        testClipboardFilter();
        Path root = args.length == 0 ? Files.createTempDirectory("wordnest-ui-test-")
                : Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "wordnest-ui-test-");
        Files.writeString(root.resolve("settings.properties"), "clipboard=false\npin=false\nlastWord=hello\n");
        MainWindow[] window = new MainWindow[1];
        try {
            onEdt(() -> window[0] = new MainWindow(root));
            awaitLoaded(window[0]);
            onEdt(() -> testFirstFavoriteWithPendingNote(window[0]));
            onEdt(() -> testSwitchFlushesNote(window[0]));
            onEdt(() -> testMissingLookupFlushesNote(window[0]));
            onEdt(() -> testAutomaticLookupKeepsState(window[0]));
            onEdt(() -> testFailedSaveKeepsCurrentNote(window[0], root));
            onEdt(() -> testFlushUsedByClosing(window[0]));
            System.out.println("PASS: " + assertions + " hidden-window state assertions");
        } finally {
            if (window[0] != null) {
                onEdt(() -> {
                    ((ClipboardWatcher) field(window[0], "watcher")).stop();
                    ((Timer) field(window[0], "saveNoteTimer")).stop();
                    window[0].dispose();
                });
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void testClipboardFilter() {
        check(ClipboardWatcher.isSingleWord("Research"), "clipboard accepts English letters");
        check(ClipboardWatcher.isSingleWord("  word\r\n"), "clipboard accepts surrounding whitespace");
        check(!ClipboardWatcher.isSingleWord(null), "clipboard ignores missing text");
        check(!ClipboardWatcher.isSingleWord(""), "clipboard ignores empty text");
        check(!ClipboardWatcher.isSingleWord("中文"), "clipboard ignores Chinese text");
        check(!ClipboardWatcher.isSingleWord("two words"), "clipboard ignores sentences and phrases");
        check(!ClipboardWatcher.isSingleWord("word\nother"), "clipboard ignores multiple lines");
        check(!ClipboardWatcher.isSingleWord("https://example.com"), "clipboard ignores addresses");
        check(!ClipboardWatcher.isSingleWord("abc123"), "clipboard ignores mixed identifiers");
        check(!ClipboardWatcher.isSingleWord("x".repeat(81)), "clipboard ignores long content");
    }

    private static void testFirstFavoriteWithPendingNote(MainWindow window) throws Exception {
        lookup(window, "hello");
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        check(!favorites.contains("hello"), "initial word is not saved");
        note(window).setText("Hello, world!\n这是一条例句。");
        // Still on the same EDT event: the 750 ms automatic save has not run.
        ((JButton) field(window, "favoriteButton")).doClick(0);
        check(favorites.contains("hello"), "clicking 收藏 with pending text keeps the favorite");
        check(favorites.get("hello").orElseThrow().note().equals("Hello, world!\n这是一条例句。"),
                "clicking 收藏 with pending text preserves the note");
        check(note(window).getText().equals("Hello, world!\n这是一条例句。"), "favorite click preserves editor text");
    }

    private static void testSwitchFlushesNote(MainWindow window) throws Exception {
        note(window).setText("修改后立即切词\nHello again.");
        lookup(window, "world");
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        check(favorites.get("hello").orElseThrow().note().equals("修改后立即切词\nHello again."),
                "switching flushes the old word note");
        check(((WordEntry) field(window, "current")).word().equalsIgnoreCase("world"), "switch opens requested word");
        check(note(window).getText().isEmpty(), "old note does not leak into the new word");
    }

    private static void testMissingLookupFlushesNote(MainWindow window) throws Exception {
        note(window).setText("A whole new world.");
        lookup(window, "wordnestnonexistenttokenzzzz");
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        check(favorites.get("world").orElseThrow().note().equals("A whole new world."), "missing search preserves old note");
        check(field(window, "current") == null, "missing manual search clears current entry");
        lookup(window, "world");
        check(note(window).getText().equals("A whole new world."), "returning to word restores saved note");
    }

    private static void testFailedSaveKeepsCurrentNote(MainWindow window, Path root) throws Exception {
        Path actual = root.resolve("favorites.tsv");
        Path original = root.resolve("favorites.original.tsv");
        Files.move(actual, original);
        Files.createDirectory(actual);
        Path marker = actual.resolve("marker");
        Files.writeString(marker, "original target remains unchanged");
        try {
            note(window).setText("这段未能落盘的文字必须保留。");
            lookup(window, "computer");
            check(((WordEntry) field(window, "current")).word().equalsIgnoreCase("world"),
                    "failed save prevents changing current word");
            check(note(window).getText().equals("这段未能落盘的文字必须保留。"), "failed save preserves editor text");
            check((boolean) field(window, "noteDirty"), "failed save leaves note dirty for retry");
            check(!(boolean) invoke(window, "flushNote", new Class<?>[0]), "close guard rejects a failed save");
        } finally {
            Files.delete(marker);
            Files.delete(actual);
            Files.move(original, actual);
        }
        lookup(window, "computer");
        check(((WordEntry) field(window, "current")).word().equalsIgnoreCase("computer"), "switch succeeds after storage recovers");
        check(new FavoritesStore(root).get("world").orElseThrow().note().equals("这段未能落盘的文字必须保留。"),
                "retry persists the previously blocked note");
    }

    private static void testAutomaticLookupKeepsState(MainWindow window) throws Exception {
        note(window).setText("自动查词不能丢掉这段内容。");
        invoke(window, "lookup", new Class<?>[]{String.class, boolean.class}, "wordnestmissingautomaticzzzz", true);
        check(((WordEntry) field(window, "current")).word().equalsIgnoreCase("world"),
                "unknown clipboard word keeps current entry");
        check(note(window).getText().equals("自动查词不能丢掉这段内容。"), "unknown clipboard word keeps editor text");
        invoke(window, "lookup", new Class<?>[]{String.class, boolean.class}, "WORLD", true);
        check((boolean) field(window, "noteDirty"), "same clipboard word does not rebuild the editor");
        check(note(window).getText().equals("自动查词不能丢掉这段内容。"), "same clipboard word keeps pending text");
        invoke(window, "lookup", new Class<?>[]{String.class, boolean.class}, "hello", true);
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        check(favorites.get("world").orElseThrow().note().equals("自动查词不能丢掉这段内容。"),
                "new clipboard word flushes pending text before switch");
        lookup(window, "world");
    }

    private static void testFlushUsedByClosing(MainWindow window) throws Exception {
        note(window).setText("A computer can store words.");
        check((boolean) invoke(window, "flushNote", new Class<?>[0]), "closing flush succeeds");
        check(!(boolean) field(window, "noteDirty"), "closing flush clears dirty state");
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        check(favorites.get("computer").orElseThrow().note().equals("A computer can store words."),
                "closing flush saves final edit");
    }

    private static void awaitLoaded(MainWindow window) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        boolean[] loaded = {false};
        while (System.nanoTime() < deadline) {
            onEdt(() -> loaded[0] = (boolean) field(window, "loaded"));
            if (loaded[0]) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Dictionary did not load within 20 seconds; include src/main/resources on classpath");
    }

    private static JTextArea note(MainWindow window) throws Exception { return (JTextArea) field(window, "noteArea"); }
    private static void lookup(MainWindow window, String text) throws Exception {
        invoke(window, "lookup", new Class<?>[]{String.class, boolean.class}, text, false);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw exception;
        }
    }
    private static void onEdt(CheckedAction action) throws Exception {
        Throwable[] failure = {null};
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Throwable cause) { failure[0] = cause; } });
        if (failure[0] instanceof Exception cause) throw cause;
        if (failure[0] instanceof Error cause) throw cause;
    }
    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
