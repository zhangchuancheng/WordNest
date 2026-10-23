package wordnest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** Pure storage tests: injected clocks and temporary files; no UI or personal data. */
public final class ReviewStoreTest {
    private static int assertions;
    private static final long START = Instant.parse("2026-10-23T08:00:00Z").toEpochMilli();

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wordnest-review-store-");
        try {
            roundsAndRestart(root.resolve("rounds"));
            intervals(root.resolve("intervals"));
            duePriorityAndCancellation(root.resolve("cancel"));
            failures(root.resolve("failures"));
            corruptFiles(root.resolve("corrupt"));
            clockRollback(root.resolve("clock-rollback"));
            System.out.println("PASS: " + assertions + " review store assertions (temporary files, injected clock)");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void roundsAndRestart(Path path) throws Exception {
        MutableClock clock = new MutableClock(START);
        ReviewStore store = new ReviewStore(path, clock);
        List<SavedWord> words = words(25);
        check(!Files.exists(path.resolve("review.tsv")), "opening a new store does not write");
        check(store.snapshot(words).newCount() == 25 && store.snapshot(words).dueCount() == 0, "unseen favorites are eligible");
        check(store.snapshot(List.of()).eligibleCount() == 0, "empty favorites has no due work");
        expectIllegal(() -> store.startRound(5, words), "only explicit 10/20 sizes are accepted");
        ReviewStore.Round first = store.startRound(10, words).round().orElseThrow();
        check(first.words().size() == 10 && first.position() == 0 && first.active(), "10-word round is finite");
        expectUnsupported(() -> first.words().add("other"), "queue snapshot cannot be modified");
        check(store.startRound(20, words).round().orElseThrow().words().size() == 10, "continuing does not replace an unfinished round");
        store.answer("word0", true, words);
        store.answer("word1", false, words);
        ReviewStore reopened = new ReviewStore(path, clock);
        ReviewStore.Round resumed = reopened.snapshot(words).round().orElseThrow();
        check(resumed.position() == 2 && resumed.remembered() == 1 && resumed.forgotten() == 1, "position and answers survive restart");
        check(resumed.currentWord().orElseThrow().equals("word2"), "restart resumes the next unanswered question");
        check(first.position() == 0, "prior round snapshots stay unchanged");
        check(reopened.progress("WORD0").orElseThrow().level() == 1, "schedule lookup ignores case");
        check(reopened.progress("word1").orElseThrow().dueAt() == START + Duration.ofMinutes(10).toMillis(), "forgotten is due in ten minutes");
        byte[] before = Files.readAllBytes(path.resolve("review.tsv"));
        reopened.answer("word0", true, words);
        check(Arrays.equals(before, Files.readAllBytes(path.resolve("review.tsv"))), "stale answer never advances or writes");
        while (reopened.snapshot(words).round().orElseThrow().active()) {
            String current = reopened.snapshot(words).round().orElseThrow().currentWord().orElseThrow();
            reopened.answer(current, true, words);
        }
        ReviewStore.Round done = reopened.snapshot(words).round().orElseThrow();
        check(done.completed() && done.position() == 10 && done.remembered() == 9 && done.forgotten() == 1, "completion has exact finite counts");
        check(done.completedAt() == START && done.currentWord().isEmpty(), "completion records time and no extra question");
        check(reopened.snapshot(words).newCount() == 15, "unselected favorites remain available for another round");
        check(reopened.startRound(20, words).round().orElseThrow().words().size() == 15, "shorter remaining queue has actual size");
        ReviewStore twenty = new ReviewStore(path.resolve("twenty"), clock);
        check(twenty.startRound(20, words).round().orElseThrow().words().size() == 20, "20 choice creates at most 20 questions");
        check(!Files.exists(path.resolve("favorites.tsv")), "review never creates or changes a favorites file");
    }

    private static void intervals(Path path) throws Exception {
        MutableClock clock = new MutableClock(START);
        ReviewStore store = new ReviewStore(path, clock);
        List<SavedWord> words = words(1);
        int[] days = {1, 3, 7, 14, 30, 60, 60};
        for (int i = 0; i < days.length; i++) {
            check(store.startRound(10, words).round().orElseThrow().active(), "due word starts a new finite round " + i);
            store.answer("word0", true, words);
            ReviewStore.Progress result = store.progress("word0").orElseThrow();
            check(result.level() == Math.min(6, i + 1), "remembered level increments with cap " + i);
            check(result.dueAt() == clock.millis() + Duration.ofDays(days[i]).toMillis(), "remembered interval follows documented schedule " + i);
            check(store.snapshot(words).eligibleCount() == 0, "future word is not immediately due " + i);
            check(store.snapshot(words).nextDue().orElseThrow().toEpochMilli() == result.dueAt(), "future due time is shown " + i);
            byte[] before = Files.readAllBytes(path.resolve("review.tsv"));
            store.startRound(10, words);
            check(Arrays.equals(before, Files.readAllBytes(path.resolve("review.tsv"))), "no due start is a no-op " + i);
            clock.millis = result.dueAt();
        }
        store.startRound(10, words); store.answer("word0", false, words);
        ReviewStore.Progress forgotten = store.progress("word0").orElseThrow();
        check(forgotten.level() == 0, "forgotten resets consecutive remembered level");
        check(forgotten.dueAt() == clock.millis() + 600_000, "forgotten interval stays ten minutes after a long streak");
        clock.millis = forgotten.dueAt() - 1;
        check(store.snapshot(words).dueCount() == 0, "not due one millisecond early");
        clock.millis++;
        check(store.snapshot(words).dueCount() == 1, "eligible exactly at the due boundary");
        store.startRound(10, words); store.answer("word0", true, words);
        check(store.progress("word0").orElseThrow().level() == 1
                && store.progress("word0").orElseThrow().dueAt() == clock.millis() + 86_400_000, "remembering after forgetting starts again at one day");
    }

    private static void duePriorityAndCancellation(Path path) throws Exception {
        MutableClock clock = new MutableClock(START);
        ReviewStore store = new ReviewStore(path, clock);
        List<SavedWord> one = words(1);
        store.startRound(10, one); store.answer("word0", false, one); clock.millis += 600_000;
        List<SavedWord> favorites = new ArrayList<>(words(5));
        java.util.Collections.reverse(favorites);
        ReviewStore.Round round = store.startRound(10, favorites).round().orElseThrow();
        check(round.currentWord().orElseThrow().equals("word0"), "overdue work comes before newly saved favorites");
        favorites.removeIf(saved -> saved.entry().word().equals("word0"));
        ReviewStore.Round skipped = store.answer("word0", true, favorites).round().orElseThrow();
        check(skipped.skipped() == 1 && skipped.position() == 1 && skipped.answered() == 0, "canceled current question is skipped without scoring next word");
        check(store.progress("word0").orElseThrow().level() == 0, "canceled question receives no new schedule");
        check(store.snapshot(favorites).dueCount() == 0, "unfavorited overdue words are excluded from counts");
        ReviewStore.Round finished = store.syncFavorites(List.of()).round().orElseThrow();
        check(finished.completed() && finished.skipped() == 5 && finished.answered() == 0, "canceling all remaining words ends the round");
        check(new ReviewStore(path, clock).snapshot(List.of()).round().orElseThrow().equals(finished), "skips and completion survive restart");
        check(store.snapshot(one).dueCount() == 1, "restoring favorite preserves its prior due schedule");
        List<SavedWord> markedFalse = List.of(new SavedWord(one.get(0).entry(), "retained", START, false));
        check(store.snapshot(markedFalse).eligibleCount() == 0, "defensively ignores retained nonfavorites");
    }

    private static void failures(Path path) throws Exception {
        MutableClock clock = new MutableClock(START);
        ReviewStore store = new ReviewStore(path, clock);
        List<SavedWord> words = words(3);
        store.startRound(10, words);
        ReviewStore.Round original = store.snapshot(words).round().orElseThrow();
        Path file = path.resolve("review.tsv"), saved = path.resolve("original.tsv");
        byte[] originalBytes = Files.readAllBytes(file);
        Files.move(file, saved); Files.createDirectory(file); Files.writeString(file.resolve("blocker"), "do not replace");
        try {
            expectIo(() -> store.answer("word0", true, words), "failed answer reports persistence failure");
            check(store.snapshot(words).round().orElseThrow().equals(original) && store.progress("word0").isEmpty(), "failed answer changes neither round nor schedule");
            expectIo(() -> store.syncFavorites(List.of()), "failed skip reports persistence failure");
            check(store.snapshot(words).round().orElseThrow().equals(original), "failed skip does not advance");
            check(Arrays.equals(originalBytes, Files.readAllBytes(saved)), "original persisted bytes remain intact");
            try (var files = Files.list(path)) {
                check(files.noneMatch(item -> item.getFileName().toString().endsWith(".tmp")), "failed writes clean their temporary files");
            }
        } finally { Files.delete(file.resolve("blocker")); Files.delete(file); Files.move(saved, file); }
        store.answer("word0", true, words);
        check(new ReviewStore(path, clock).snapshot(words).round().orElseThrow().position() == 1, "retry persists exactly one answer");
        Path create = path.resolve("start");
        ReviewStore fresh = new ReviewStore(create, clock);
        Files.createDirectory(create.resolve("review.tsv")); Files.writeString(create.resolve("review.tsv/blocker"), "blocked");
        expectIo(() -> fresh.startRound(10, words), "failed start is reported");
        check(fresh.snapshot(words).round().isEmpty(), "failed start does not create an in-memory round");
    }

    private static void corruptFiles(Path root) throws Exception {
        String header = "WORDNEST-REVIEW\t1\n", word = b64("word0");
        List<String> corrupt = List.of("", "WORDNEST-REVIEW\t2\n", header + "\n", header + "P\t!!!\t1\t10\t0\n",
                header + "P\t" + word + "\t7\t10\t0\n", header + "P\t" + word + "\t1\t-1\t0\n",
                header + "P\t" + word + "\t1\t5\t10\n", header + "P\t" + word + "\t1\t10\t0\nP\t" + b64("WORD0") + "\t1\t10\t0\n",
                header + "Q\t" + word + "\n", header + "R\t0\t0\t0\t0\t10\t0\n",
                header + "R\t1\t0\t0\t0\t10\t10\nQ\t" + word + "\n",
                header + "R\t0\t0\t0\t0\t10\t10\nQ\t" + word + "\n",
                header + "R\t1\t1\t0\t0\t10\t9\nQ\t" + word + "\n",
                header + "R\t0\t0\t0\t0\t10\t0\nQ\t" + word + "\nQ\t" + b64("WORD0") + "\n",
                header + "P\t" + b64(" bad ") + "\t1\t10\t0\n", header + "P\t/w==\t1\t10\t0\n",
                header + "R\t0\t0\t0\t0\t10\t0\nQ\t" + word + "\nP\t" + word + "\t1\t10\t0\n");
        for (int index = 0; index < corrupt.size(); index++) {
            Path directory = Files.createDirectories(root.resolve("case" + index));
            Path file = directory.resolve("review.tsv"); Files.writeString(file, corrupt.get(index));
            byte[] original = Files.readAllBytes(file);
            expectIo(() -> new ReviewStore(directory), "corrupt review rejected " + index);
            check(Arrays.equals(original, Files.readAllBytes(file)), "corrupt original preserved " + index);
        }
        Path invalidUtf = Files.createDirectories(root.resolve("invalid-utf"));
        Files.write(invalidUtf.resolve("review.tsv"), new byte[]{(byte) 0xff});
        expectIo(() -> new ReviewStore(invalidUtf), "invalid outer UTF-8 is rejected");
    }

    private static void clockRollback(Path root) throws Exception {
        MutableClock clock = new MutableClock(START);
        ReviewStore store = new ReviewStore(root, clock);
        List<SavedWord> words = words(1);
        store.startRound(10, words);
        clock.millis -= 3_600_000;
        store.answer("word0", true, words);
        check(new ReviewStore(root, clock).snapshot(words).round().orElseThrow().completed(),
                "system clock rollback cannot make a just-saved completed round unreadable");
    }

    static List<SavedWord> words(int count) {
        List<SavedWord> words = new ArrayList<>();
        for (int i = 0; i < count; i++) words.add(new SavedWord(new WordEntry("word" + i, "", "中文 " + i, "definition", "", ""), "例句 " + i, START));
        return words;
    }
    private static String b64(String text) { return Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
    private static void expectIo(Action action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); } catch (IOException expected) { assertions++; }
    }
    private static void expectIllegal(Action action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); } catch (IllegalArgumentException expected) { assertions++; }
    }
    private static void expectUnsupported(Action action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); } catch (UnsupportedOperationException expected) { assertions++; }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    static final class MutableClock extends Clock {
        long millis;
        MutableClock(long millis) { this.millis = millis; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
