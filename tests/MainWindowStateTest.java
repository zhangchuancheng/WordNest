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
import java.util.Arrays;
import java.util.Comparator;

/**
 * Hidden-window state regression definitions; these construct JFrame instances.
 * Do not run under a no-desktop verification boundary. They never show/activate
 * a window, register a tray icon, or read/change the clipboard; all data are temporary.
 */
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
            onEdt(() -> testCancelWithPendingNote(window[0], root));
            onEdt(() -> testRetainedEditingAndRestoreLatest(window[0], root));
            onEdt(() -> testUndoAcrossWords(window[0], root));
            onEdt(() -> testUndoFromMissingResult(window[0]));
            onEdt(() -> testHistorySaveFailureKeepsPosition(window[0], root));
            testBadHistoryDoesNotBlockFavorites(root.resolve("bad-history"));
            System.out.println("PASS: " + assertions + " hidden-window state assertions");
        } finally {
            dispose(window[0]);
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
        LookupHistory history = (LookupHistory) field(window, "history");
        var priorBackTarget = history.peekBack();
        invoke(window, "navigateHistory", new Class<?>[]{boolean.class}, false);
        check(((WordEntry) field(window, "current")).word().equalsIgnoreCase("world"),
                "back from a missing result reopens the last successful word");
        check(history.current().orElseThrow().equalsIgnoreCase("world") && history.peekBack().equals(priorBackTarget),
                "back from a missing result does not skip the last successful history position");
        check(note(window).getText().equals("A whole new world."), "back from a missing result restores its saved note");
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

    private static void testCancelWithPendingNote(MainWindow window, Path root) throws Exception {
        lookup(window, "hello");
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        check(favorites.contains("hello"), "cancel fixture starts as a favorite");
        note(window).setText("取消收藏前刚输入的例句。\nHello once more.");
        ((JButton) field(window, "favoriteButton")).doClick(0);
        check(!favorites.contains("hello"), "cancel with a pending note removes favorite membership");
        check(favorites.get("hello").orElseThrow().note().equals(note(window).getText()),
                "cancel saves and retains the pending note");
        check(!favorites.get("hello").orElseThrow().favorite(), "retained entry records nonfavorite state");
        check(((JButton) field(window, "favoriteButton")).getText().equals("收藏"),
                "cancel immediately updates the favorite button");
        check(((JButton) field(window, "undoFavorite")).isVisible(), "cancel offers undo");
        FavoritesStore reopened = new FavoritesStore(root);
        check(!reopened.contains("hello") && reopened.get("hello").orElseThrow().note().equals(note(window).getText()),
                "cancel and its pending note survive reloading storage");
    }

    private static void testRetainedEditingAndRestoreLatest(MainWindow window, Path root) throws Exception {
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        note(window).setText("取消收藏后继续编辑的例句。");
        lookup(window, "world");
        check(!favorites.contains("hello"), "editing a retained note does not recollect the word");
        FavoritesStore reopened = new FavoritesStore(root);
        check(!reopened.contains("hello") && reopened.get("hello").orElseThrow().note().equals("取消收藏后继续编辑的例句。"),
                "edited nonfavorite state and note both persist");
        lookup(window, "hello");
        check(note(window).getText().equals("取消收藏后继续编辑的例句。"), "lookup reloads the retained note");
        note(window).setText("恢复时还有尚未自动保存的新内容。");
        invoke(window, "restoreFavorite", new Class<?>[]{String.class}, "hello");
        check(favorites.contains("hello"), "restore recollects the word");
        check(favorites.get("hello").orElseThrow().note().equals("恢复时还有尚未自动保存的新内容。"),
                "restore uses the latest pending note rather than the canceled snapshot");
        check(note(window).getText().equals("恢复时还有尚未自动保存的新内容。") && !(boolean) field(window, "noteDirty"),
                "restoring the current word keeps the editor aligned with saved content");
    }

    private static void testUndoAcrossWords(MainWindow window, Path root) throws Exception {
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        note(window).setText("The hello note to restore.");
        ((JButton) field(window, "favoriteButton")).doClick(0);
        lookup(window, "world");
        note(window).setText("撤销另一个词之前，当前词也有未保存修改。");
        ((JButton) field(window, "undoFavorite")).doClick(0);
        check(((WordEntry) field(window, "current")).word().equalsIgnoreCase("world"),
                "undoing another word does not navigate away");
        check(note(window).getText().equals("撤销另一个词之前，当前词也有未保存修改。"),
                "undoing another word does not overwrite the current editor");
        check(new FavoritesStore(root).get("world").orElseThrow().note().equals(note(window).getText()),
                "cross-word undo flushes the current word first");
        check(favorites.contains("hello") && favorites.get("hello").orElseThrow().note().equals("The hello note to restore."),
                "cross-word undo restores the retained target note");
        check(!((JButton) field(window, "undoFavorite")).isVisible(), "successful undo clears the last cancel action");
    }

    private static void testHistorySaveFailureKeepsPosition(MainWindow window, Path root) throws Exception {
        lookup(window, "hello");
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        check(favorites.contains("hello"), "failed undo fixture starts with a favorite");
        ((JButton) field(window, "favoriteButton")).doClick(0);
        lookup(window, "world");
        LookupHistory history = (LookupHistory) field(window, "history");
        check(history.peekBack().orElseThrow().equalsIgnoreCase("hello"), "history fixture has a back target");
        var recentBefore = history.recent();
        Path actual = root.resolve("favorites.tsv");
        Path original = root.resolve("favorites.history-test.tsv");
        Files.move(actual, original);
        Files.createDirectory(actual);
        Path marker = actual.resolve("marker");
        Files.writeString(marker, "block replacing the favorites file");
        try {
            note(window).setText("历史返回前保存失败时不能丢掉这段文字。");
            ((JButton) field(window, "favoriteButton")).doClick(0);
            check(favorites.contains("world"), "failed pending-note save prevents canceling the current favorite");
            ((JButton) field(window, "undoFavorite")).doClick(0);
            check(!favorites.contains("hello") && ((JButton) field(window, "undoFavorite")).isVisible(),
                    "failed current-note save prevents cross-word undo and preserves the retry action");
            invoke(window, "navigateHistory", new Class<?>[]{boolean.class}, false);
            check(((WordEntry) field(window, "current")).word().equalsIgnoreCase("world"),
                    "failed note save blocks history navigation");
            check(history.current().orElseThrow().equalsIgnoreCase("world")
                            && history.peekBack().orElseThrow().equalsIgnoreCase("hello"),
                    "failed note save does not consume the back target");
            check((boolean) field(window, "noteDirty")
                            && note(window).getText().equals("历史返回前保存失败时不能丢掉这段文字。"),
                    "blocked history navigation retains the dirty editor");
        } finally {
            Files.delete(marker);
            Files.delete(actual);
            Files.move(original, actual);
        }
        invoke(window, "navigateHistory", new Class<?>[]{boolean.class}, false);
        check(((WordEntry) field(window, "current")).word().equalsIgnoreCase("hello"),
                "back retries the original target after storage recovers");
        check(new FavoritesStore(root).get("world").orElseThrow().note().equals("历史返回前保存失败时不能丢掉这段文字。"),
                "successful back retry saves the blocked edit");
        check(history.recent().equals(recentBefore), "back navigation does not reorder recent lookups");
        ((JButton) field(window, "undoFavorite")).doClick(0);
        check(favorites.contains("hello"), "undo remains usable after storage recovers");
    }

    private static void testUndoFromMissingResult(MainWindow window) throws Exception {
        lookup(window, "hello");
        FavoritesStore favorites = (FavoritesStore) field(window, "favorites");
        note(window).setText("从无结果页面撤销时仍应恢复这条最新笔记。");
        ((JButton) field(window, "favoriteButton")).doClick(0);
        lookup(window, "wordnestmissingbeforeundozzzz");
        check(field(window, "current") == null, "undo missing-page fixture has no current entry");
        ((JButton) field(window, "undoFavorite")).doClick(0);
        check(favorites.contains("hello")
                        && favorites.get("hello").orElseThrow().note().equals("从无结果页面撤销时仍应恢复这条最新笔记。"),
                "undo from a missing result restores the latest retained note");
        check(field(window, "current") == null && !((JButton) field(window, "undoFavorite")).isVisible(),
                "undo from a missing result does not access absent word widgets or change pages");
    }

    private static void testBadHistoryDoesNotBlockFavorites(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.properties"), "clipboard=false\npin=false\nlastWord=hello\n");
        FavoritesStore seeded = new FavoritesStore(root);
        seeded.setFavorite(new WordEntry("hello", "", "你好", "greeting", "", ""), "历史损坏也应保留这条例句。", false);
        Path historyFile = root.resolve("history.tsv");
        Files.writeString(historyFile, "WORDNEST-HISTORY\t1\nnot valid base64!!\n");
        byte[] originalHistory = Files.readAllBytes(historyFile);
        byte[] originalFavorites = Files.readAllBytes(root.resolve("favorites.tsv"));
        MainWindow[] window = new MainWindow[1];
        try {
            onEdt(() -> window[0] = new MainWindow(root));
            awaitLoaded(window[0]);
            onEdt(() -> {
                FavoritesStore favorites = (FavoritesStore) field(window[0], "favorites");
                check(!favorites.contains("hello") && note(window[0]).getText().equals("历史损坏也应保留这条例句。"),
                        "invalid history still loads retained notes and membership");
                check(Arrays.equals(Files.readAllBytes(root.resolve("favorites.tsv")), originalFavorites),
                        "history fallback does not rewrite favorites during startup");
                lookup(window[0], "world");
                check(((LookupHistory) field(window[0], "history")).recent().contains("world"),
                        "invalid history falls back to usable session memory");
                note(window[0]).setText("历史损坏后仍然可以保存生词和例句。");
                check((boolean) invoke(window[0], "flushNote", new Class<?>[0]),
                        "history fallback does not prevent personal note saves");
                check(new FavoritesStore(root).get("world").orElseThrow().note().equals(note(window[0]).getText()),
                        "personal note saves remain persistent in history fallback");
                check(Arrays.equals(Files.readAllBytes(historyFile), originalHistory),
                        "session fallback preserves the invalid history bytes");
                check(!new FavoritesStore(root).contains("hello"), "saving another word does not recollect the retained word");
            });
        } finally {
            dispose(window[0]);
        }
    }

    private static void dispose(MainWindow window) throws Exception {
        if (window == null) return;
        onEdt(() -> {
            ((ClipboardWatcher) field(window, "watcher")).stop();
            ((Timer) field(window, "saveNoteTimer")).stop();
            window.dispose();
        });
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
