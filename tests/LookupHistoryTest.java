package wordnest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Headless history tests; all persistent state is confined to a temporary directory. */
public final class LookupHistoryTest {
    private static final String HEADER = "WORDNEST-HISTORY\t1\n";
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wordnest-history-test-");
        try {
            testPersistenceAndLimit(root.resolve("recent"));
            testNavigationAndStartup(root.resolve("navigation"));
            testUnicodeAndInvalidWords(root.resolve("unicode"));
            testCorruptFiles(root.resolve("corrupt"));
            testFailedWrites(root.resolve("failed-write"));
            testSessionOnly();
            System.out.println("PASS: " + assertions + " lookup history assertions (headless, temporary data only)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void testPersistenceAndLimit(Path directory) throws Exception {
        LookupHistory history = new LookupHistory(directory);
        check(history.recent().isEmpty(), "new history is empty");
        check(history.current().isEmpty() && !history.canBack() && !history.canForward(), "new session is empty");
        check(!Files.exists(directory.resolve("history.tsv")), "opening history does not create a history file");
        for (int index = 0; index < 105; index++) history.record("word" + index);
        check(history.recent().size() == 100, "only 100 distinct recent words are kept");
        check(history.recent().get(0).equals("word104"), "newest query is first");
        check(history.recent().get(99).equals("word5"), "oldest recent words are evicted");
        List<String> oldSnapshot = history.recent();
        history.record("WORD42");
        check(history.recent().get(0).equals("WORD42"), "revisit moves canonical display spelling to front");
        check(history.recent().size() == 100, "case-insensitive revisit does not add a duplicate");
        check(history.recent().stream().filter(word -> word.equalsIgnoreCase("word42")).count() == 1,
                "case-insensitive word appears once");
        check(history.recent().get(99).equals("word5"), "revisit does not evict an unrelated word");
        check(oldSnapshot.get(0).equals("word104"), "old snapshots remain unchanged");
        expectUnsupported(() -> history.recent().add("mutation"), "recent snapshot is immutable");
        byte[] saved = Files.readAllBytes(directory.resolve("history.tsv"));
        history.record("WORD42");
        check(Arrays.equals(saved, Files.readAllBytes(directory.resolve("history.tsv"))), "consecutive repeat keeps saved order");
        check(history.peekBack().orElseThrow().equals("word104"), "consecutive repeat adds no navigation step");
        LookupHistory reopened = new LookupHistory(directory);
        check(reopened.recent().equals(history.recent()), "recent lookups survive restart in exact order");
        check(reopened.current().isEmpty() && !reopened.canBack() && !reopened.canForward(),
                "prior session navigation is not restored");
        history.clear();
        check(history.recent().isEmpty() && history.current().isEmpty(), "clear removes recent and session data");
        check(!history.canBack() && !history.canForward(), "clear resets navigation");
        check(new LookupHistory(directory).recent().isEmpty(), "clear survives restart");
        check(Files.readString(directory.resolve("history.tsv")).strip().equals(HEADER.strip()), "cleared file has only its format header");
        check(noTemporaryFiles(directory), "successful writes leave no temporary files");
    }

    private static void testNavigationAndStartup(Path directory) throws Exception {
        LookupHistory history = new LookupHistory(directory);
        history.record("old");
        history.record("recent");
        history = new LookupHistory(directory);
        byte[] saved = Files.readAllBytes(directory.resolve("history.tsv"));
        history.seedSession(history.recent().get(0));
        check(history.current().orElseThrow().equals("recent"), "startup can display latest persisted word");
        check(!history.canBack() && !history.canForward(), "startup word is a single session origin");
        check(history.recent().equals(List.of("recent", "old")), "startup does not count another query");
        check(Arrays.equals(saved, Files.readAllBytes(directory.resolve("history.tsv"))), "startup does not rewrite persistence");
        history.seedSession("fallback");
        check(history.recent().equals(List.of("recent", "old")), "startup fallback is not added to recent history");
        history.record("apple");
        history.record("banana");
        history.record("cherry");
        List<String> recent = history.recent();
        check(history.peekBack().orElseThrow().equals("banana"), "peek gives target without moving");
        check(history.peekBack().orElseThrow().equals("banana") && history.current().orElseThrow().equals("cherry"),
                "failed note save can leave the peeked navigation uncommitted");
        check(!history.commitBack("apple") && !history.commitBack(null), "mismatched target cannot commit navigation");
        check(history.current().orElseThrow().equals("cherry"), "invalid commit keeps displayed position");
        check(history.commitBack(" BANANA "), "canonical target comparison ignores case and surrounding space");
        check(history.current().orElseThrow().equals("banana") && history.canForward(), "back updates the session position");
        check(history.recent().equals(recent), "back does not reorder recent queries");
        history.record("banana");
        check(history.peekBack().orElseThrow().equals("apple") && history.peekForward().orElseThrow().equals("cherry"),
                "consecutive current-word repeat preserves both navigation directions");
        check(history.recent().get(0).equals("banana"), "explicit query still refreshes its MRU position");
        check(!history.commitForward("apple"), "wrong forward target does not move");
        check(history.commitForward("cherry"), "forward reaches previous position");
        check(!history.canForward() && !history.commitForward("cherry"), "forward is bounded at the end");
        check(history.commitBack("banana"), "back before branching succeeds");
        history.record("date");
        check(!history.canForward() && history.peekForward().isEmpty(), "new query after back discards forward branch");
        check(history.peekBack().orElseThrow().equals("banana"), "new query retains the preceding back path");
        check(history.recent().contains("cherry"), "session branching does not remove a distinct recent lookup");
        check(history.commitBack("banana") && history.commitBack("apple") && history.commitBack("fallback"),
                "back navigates through the full retained path");
        check(!history.canBack() && history.peekBack().isEmpty() && !history.commitBack("missing"), "back is bounded at origin");
        saved = Files.readAllBytes(directory.resolve("history.tsv"));
        history.recordSession("recovered");
        check(history.current().orElseThrow().equals("recovered") && !history.canForward(), "session recovery branches from current position");
        check(!history.recent().contains("recovered"), "session recovery does not claim a saved recent lookup");
        check(Arrays.equals(saved, Files.readAllBytes(directory.resolve("history.tsv"))), "session recovery does not write persistence");
    }

    private static void testUnicodeAndInvalidWords(Path directory) throws Exception {
        LookupHistory history = new LookupHistory(directory);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            for (String word : List.of("  ITEM  ", "naïve", "café", "中文词条", "𠮷", "don't", "well-being", "in spite of")) {
                history.record(word);
            }
            history.record("item");
        } finally {
            Locale.setDefault(previous);
        }
        check(history.recent().size() == 8, "locale-independent case normalization deduplicates ITEM");
        check(history.recent().get(0).equals("item"), "trimmed canonical casing is retained");
        check(new LookupHistory(directory).recent().equals(history.recent()), "Unicode, supplementary characters and phrases round-trip");
        List<String> recent = history.recent();
        String current = history.current().orElseThrow();
        byte[] saved = Files.readAllBytes(directory.resolve("history.tsv"));
        List<String> invalid = new ArrayList<>(List.of("", "  ", "\u2003", "line\nbreak", "tab\tword", "null\0word",
                "line\u2028separator", "para\u2029separator", "x".repeat(121), "unpaired\uD800", "unpaired\uDC00"));
        invalid.add(null);
        for (String word : invalid) {
            expectIllegalArgument(() -> history.record(word), "invalid record rejected");
            expectIllegalArgument(() -> history.recordSession(word), "invalid recovery word rejected");
            expectIllegalArgument(() -> history.seedSession(word), "invalid seed rejected");
        }
        check(history.recent().equals(recent) && history.current().orElseThrow().equals(current), "invalid words cannot mutate either history");
        check(Arrays.equals(saved, Files.readAllBytes(directory.resolve("history.tsv"))), "invalid words leave saved bytes unchanged");
        history.record("x".repeat(120));
        check(history.recent().get(0).length() == 120, "maximum valid headword length is accepted");
    }

    private static void testCorruptFiles(Path root) throws Exception {
        List<byte[]> cases = new ArrayList<>();
        cases.add(new byte[0]);
        cases.add("WRONG-HEADER\n".getBytes(StandardCharsets.UTF_8));
        cases.add("WORDNEST-HISTORY\t2\n".getBytes(StandardCharsets.UTF_8));
        cases.add((HEADER + "%%%\n").getBytes(StandardCharsets.UTF_8));
        cases.add((HEADER + "Y\n").getBytes(StandardCharsets.UTF_8));
        cases.add((HEADER + encoded("apple") + "\n" + encoded("APPLE") + "\n").getBytes(StandardCharsets.UTF_8));
        cases.add((HEADER + "\n").getBytes(StandardCharsets.UTF_8));
        cases.add((HEADER + encoded(" apple ") + "\n").getBytes(StandardCharsets.UTF_8));
        cases.add((HEADER + encoded("bad\nword") + "\n").getBytes(StandardCharsets.UTF_8));
        cases.add((HEADER + encoded("x".repeat(121)) + "\n").getBytes(StandardCharsets.UTF_8));
        cases.add((HEADER + Base64.getEncoder().encodeToString(new byte[]{(byte) 0xC3, 0x28}) + "\n").getBytes(StandardCharsets.UTF_8));
        cases.add(new byte[]{(byte) 0xFF});
        cases.add((HEADER + "x".repeat(65_536)).getBytes(StandardCharsets.UTF_8));
        StringBuilder tooMany = new StringBuilder(HEADER);
        for (int index = 0; index < 101; index++) tooMany.append(encoded("word" + index)).append('\n');
        cases.add(tooMany.toString().getBytes(StandardCharsets.UTF_8));
        for (int index = 0; index < cases.size(); index++) {
            Path directory = Files.createDirectories(root.resolve("case-" + index));
            Path file = directory.resolve("history.tsv");
            byte[] bytes = cases.get(index);
            Files.write(file, bytes);
            expectIOException(() -> new LookupHistory(directory), "corrupt file rejected: " + index);
            check(Arrays.equals(bytes, Files.readAllBytes(file)), "corrupt file preserved byte-for-byte: " + index);
            LookupHistory fallback = LookupHistory.sessionOnly();
            fallback.seedSession("fallback");
            fallback.record("works");
            fallback.clear();
            check(Arrays.equals(bytes, Files.readAllBytes(file)), "session fallback cannot overwrite corrupt source: " + index);
        }
        Path directory = Files.createDirectories(root.resolve("path-is-directory"));
        Files.createDirectory(directory.resolve("history.tsv"));
        expectIOException(() -> new LookupHistory(directory), "history path that is a directory is reported");
        check(Files.isDirectory(directory.resolve("history.tsv")), "unexpected history directory is preserved");
    }

    private static void testFailedWrites(Path directory) throws Exception {
        LookupHistory history = new LookupHistory(directory);
        history.seedSession("start");
        history.record("apple");
        history.record("banana");
        check(history.commitBack("apple"), "prepare a forward branch before failed persistence");
        List<String> recent = history.recent();
        Path file = directory.resolve("history.tsv");
        byte[] saved = Files.readAllBytes(file);
        Path preserved = directory.resolve("saved-history.tsv");
        Files.move(file, preserved);
        Files.createDirectory(file);
        Path marker = file.resolve("keep.txt");
        Files.writeString(marker, "preserve this directory");
        expectIOException(() -> history.record("cherry"), "failed atomic replacement is reported");
        check(history.recent().equals(recent), "failed record preserves in-memory recent snapshot");
        check(history.current().orElseThrow().equals("apple") && history.peekForward().orElseThrow().equals("banana"),
                "failed record preserves navigation position and forward branch");
        expectIOException(history::clear, "failed clear is reported");
        check(history.recent().equals(recent) && history.current().orElseThrow().equals("apple") && history.canForward(),
                "failed clear preserves every in-memory state");
        check(Arrays.equals(saved, Files.readAllBytes(preserved)), "failed replacement leaves previous saved data intact");
        check(Files.readString(marker).equals("preserve this directory"), "failed replacement leaves existing target intact");
        check(noTemporaryFiles(directory), "failed writes clean temporary files");
        history.recordSession("cherry");
        check(history.current().orElseThrow().equals("cherry") && !history.canForward(), "caller can recover successful display with session-only record");
        check(history.recent().equals(recent), "explicit session recovery does not change persistent snapshot");
        check(history.commitBack("apple") && history.commitForward("cherry"), "session-only recovery still supports navigation");
        Files.delete(marker);
        Files.delete(file);
        Files.move(preserved, file);
        history.record("date");
        check(new LookupHistory(directory).recent().equals(history.recent()), "subsequent writes can recover after I/O failure");
        history.clear();
        check(new LookupHistory(directory).recent().isEmpty(), "clear succeeds after storage recovers");
    }

    private static void testSessionOnly() throws Exception {
        LookupHistory history = LookupHistory.sessionOnly();
        history.seedSession("startup");
        check(history.recent().isEmpty(), "fallback startup is not a recent query");
        for (int index = 0; index < 102; index++) history.record("word" + index);
        check(history.recent().size() == 100 && history.recent().get(0).equals("word101"), "session-only recent list uses same limit and ordering");
        history.record("WORD100");
        check(history.recent().size() == 100 && history.recent().get(0).equals("WORD100"), "fallback recent list deduplicates normally");
        check(history.commitBack("word101") && history.commitForward("WORD100"), "fallback navigation works normally");
        int steps = 0;
        while (history.canBack()) {
            check(history.commitBack(history.peekBack().orElseThrow()), "navigate fallback back path");
            steps++;
        }
        check(steps == 103 && history.current().orElseThrow().equals("startup"), "session navigation is independent of the recent 100 limit");
        history.clear();
        check(history.recent().isEmpty() && history.current().isEmpty() && !history.canForward(), "fallback clear resets memory");
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean noTemporaryFiles(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.noneMatch(path -> path.getFileName().toString().startsWith(".history-"));
        }
    }

    private static void expectIOException(CheckedAction action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); }
        catch (IOException expected) { check(true, label); }
    }

    private static void expectIllegalArgument(CheckedAction action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException expected) { check(true, label); }
    }

    private static void expectUnsupported(CheckedAction action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); }
        catch (UnsupportedOperationException expected) { check(true, label); }
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
