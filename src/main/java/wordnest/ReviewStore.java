package wordnest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A small explicit interval schedule and a resumable, finite round; independent of favorites.tsv. */
public final class ReviewStore {
    private static final String HEADER = "WORDNEST-REVIEW\t1";
    private static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final int[] DAYS = {1, 3, 7, 14, 30, 60};
    private final Path directory;
    private final Path file;
    private final Clock clock;
    private Map<String, Progress> progress = Map.of();
    private Round round;

    /** level counts consecutive remembered answers, capped at six; forgotten resets it to zero. */
    public record Progress(String word, int level, long dueAt, long lastReviewedAt) { }

    public record Round(List<String> words, int position, int remembered, int forgotten, int skipped,
                        long startedAt, long completedAt) {
        public Round { words = List.copyOf(words); }
        public boolean active() { return position < words.size(); }
        public boolean completed() { return !active(); }
        public int answered() { return remembered + forgotten; }
        public Optional<String> currentWord() { return active() ? Optional.of(words.get(position)) : Optional.empty(); }
    }

    public record Snapshot(Optional<Round> round, int dueCount, int newCount, Optional<Instant> nextDue) {
        public int eligibleCount() { return dueCount + newCount; }
    }

    public ReviewStore(Path directory) throws IOException { this(directory, Clock.systemDefaultZone()); }

    public ReviewStore(Path directory, Clock clock) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.file = this.directory.resolve("review.tsv");
        this.clock = Objects.requireNonNull(clock, "clock");
        Files.createDirectories(this.directory);
        load();
    }

    /** Read-only schedule information, considering only words that are still favorites. */
    public synchronized Snapshot snapshot(List<SavedWord> favorites) {
        Map<String, SavedWord> current = favoriteWords(favorites);
        int due = 0, fresh = 0;
        long now = now(), next = Long.MAX_VALUE;
        for (String key : current.keySet()) {
            Progress item = progress.get(key);
            if (item == null) fresh++;
            else if (item.dueAt() <= now) due++;
            else next = Math.min(next, item.dueAt());
        }
        return new Snapshot(Optional.ofNullable(round), due, fresh,
                next == Long.MAX_VALUE ? Optional.empty() : Optional.of(Instant.ofEpochMilli(next)));
    }

    public synchronized Optional<Progress> progress(String word) {
        return Optional.ofNullable(progress.get(key(Objects.requireNonNull(word, "word"))));
    }

    /** An unfinished round always takes precedence over starting another one. */
    public synchronized Snapshot startRound(int limit, List<SavedWord> favorites) throws IOException {
        if (limit != 10 && limit != 20) throw new IllegalArgumentException("A review round must contain up to 10 or 20 words");
        Map<String, SavedWord> current = favoriteWords(favorites);
        if (round != null && round.active()) return syncFavorites(favorites);
        long now = now();
        List<String> due = new ArrayList<>(), fresh = new ArrayList<>();
        for (var item : current.entrySet()) {
            Progress schedule = progress.get(item.getKey());
            if (schedule == null) fresh.add(item.getValue().entry().word());
            else if (schedule.dueAt() <= now) due.add(item.getValue().entry().word());
        }
        // Oldest overdue words first; unseen words then retain the current favorites ordering.
        due.sort(Comparator.comparingLong(word -> progress.get(key(word)).dueAt()));
        due.addAll(fresh);
        if (due.isEmpty()) return snapshot(favorites);
        Round created = new Round(due.subList(0, Math.min(limit, due.size())), 0, 0, 0, 0, now, 0);
        commit(progress, created);
        return snapshot(favorites);
    }

    /** Skips canceled words at the current position without marking them remembered or forgotten. */
    public synchronized Snapshot syncFavorites(List<SavedWord> favorites) throws IOException {
        Round updated = skipCanceled(round, favoriteWords(favorites), now());
        if (!Objects.equals(updated, round)) commit(progress, updated);
        return snapshot(favorites);
    }

    /**
     * Persist the schedule and position together before accepting an answer. The expected word
     * prevents a stale button click from answering the next question after a cancellation.
     */
    public synchronized Snapshot answer(String expectedWord, boolean remembered, List<SavedWord> favorites) throws IOException {
        Objects.requireNonNull(expectedWord, "expectedWord");
        Map<String, SavedWord> current = favoriteWords(favorites);
        long now = now();
        Round updated = skipCanceled(round, current, now);
        if (!Objects.equals(updated, round)) {
            commit(progress, updated);
            return snapshot(favorites);
        }
        if (round == null || !round.active() || !key(round.currentWord().orElseThrow()).equals(key(expectedWord)))
            return snapshot(favorites);
        String word = round.currentWord().orElseThrow();
        Progress prior = progress.get(key(word));
        int level = remembered ? Math.min(DAYS.length, (prior == null ? 0 : prior.level()) + 1) : 0;
        long interval = remembered ? Duration.ofDays(DAYS[level - 1]).toMillis() : Duration.ofMinutes(10).toMillis();
        long dueAt = Math.addExact(now, interval);
        Map<String, Progress> schedules = new HashMap<>(progress);
        schedules.put(key(word), new Progress(word, level, dueAt, now));
        int position = round.position() + 1;
        updated = new Round(round.words(), position, round.remembered() + (remembered ? 1 : 0),
                round.forgotten() + (remembered ? 0 : 1), round.skipped(), round.startedAt(),
                position == round.words().size() ? Math.max(now, round.startedAt()) : 0);
        updated = skipCanceled(updated, current, now);
        commit(schedules, updated);
        return snapshot(favorites);
    }

    private Round skipCanceled(Round prior, Map<String, SavedWord> favorites, long now) {
        if (prior == null || !prior.active()) return prior;
        int position = prior.position();
        while (position < prior.words().size() && !favorites.containsKey(key(prior.words().get(position)))) position++;
        if (position == prior.position()) return prior;
        return new Round(prior.words(), position, prior.remembered(), prior.forgotten(),
                prior.skipped() + position - prior.position(), prior.startedAt(),
                position == prior.words().size() ? Math.max(now, prior.startedAt()) : 0);
    }

    private void commit(Map<String, Progress> schedules, Round updated) throws IOException {
        persist(schedules, updated);
        progress = Map.copyOf(schedules);
        round = updated;
    }

    private long now() {
        long value = clock.millis();
        if (value < 0) throw new IllegalStateException("Review clock must not precede the Unix epoch");
        return value;
    }

    private static Map<String, SavedWord> favoriteWords(List<SavedWord> favorites) {
        Objects.requireNonNull(favorites, "favorites");
        Map<String, SavedWord> result = new LinkedHashMap<>();
        for (SavedWord saved : favorites) if (saved.favorite()) {
            String word = validWord(saved.entry().word());
            result.putIfAbsent(key(word), saved);
        }
        return result;
    }

    private void load() throws IOException {
        if (Files.notExists(file)) return;
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_FILE_BYTES + 1); }
        if (bytes.length > MAX_FILE_BYTES) throw invalid("文件过大", null);
        List<String> lines;
        try { lines = decodeUtf8(bytes).lines().toList(); }
        catch (CharacterCodingException ex) { throw invalid("不是有效的 UTF-8 文本", ex); }
        if (lines.isEmpty() || !lines.get(0).equals(HEADER)) throw invalid("格式不正确", null);
        Map<String, Progress> loaded = new HashMap<>();
        String[] roundFields = null;
        List<String> words = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        try {
            for (int i = 1; i < lines.size(); i++) {
                String[] fields = lines.get(i).split("\t", -1);
                if (fields.length == 5 && fields[0].equals("P") && roundFields == null) {
                    String word = validWord(decode(fields[1]));
                    int level = Integer.parseInt(fields[2]);
                    long due = Long.parseLong(fields[3]), reviewed = Long.parseLong(fields[4]);
                    if (level < 0 || level > DAYS.length || reviewed < 0 || due < reviewed
                            || loaded.putIfAbsent(key(word), new Progress(word, level, due, reviewed)) != null)
                        throw new IllegalArgumentException("Invalid or duplicate schedule");
                } else if (fields.length == 7 && fields[0].equals("R") && roundFields == null) {
                    roundFields = fields;
                } else if (fields.length == 2 && fields[0].equals("Q") && roundFields != null) {
                    String word = validWord(decode(fields[1]));
                    if (words.size() == 20 || !seen.add(key(word))) throw new IllegalArgumentException("Invalid round queue");
                    words.add(word);
                } else throw new IllegalArgumentException("Invalid review record");
            }
            Round loadedRound = null;
            if (roundFields != null) {
                int position = Integer.parseInt(roundFields[1]), remembered = Integer.parseInt(roundFields[2]),
                        forgotten = Integer.parseInt(roundFields[3]), skipped = Integer.parseInt(roundFields[4]);
                long started = Long.parseLong(roundFields[5]), completed = Long.parseLong(roundFields[6]);
                if (words.isEmpty() || position < 0 || position > words.size() || remembered < 0 || forgotten < 0 || skipped < 0
                        || (long) remembered + forgotten + skipped != position || started < 0
                        || (position < words.size() ? completed != 0 : completed < started))
                    throw new IllegalArgumentException("Invalid round progress");
                loadedRound = new Round(words, position, remembered, forgotten, skipped, started, completed);
            }
            progress = Map.copyOf(loaded);
            round = loadedRound;
        } catch (IllegalArgumentException | CharacterCodingException ex) {
            throw invalid("内容损坏", ex);
        }
    }

    private IOException invalid(String reason, Exception cause) {
        return new IOException("复习进度" + reason + "，原文件已保留：" + file, cause);
    }

    private void persist(Map<String, Progress> schedules, Round savedRound) throws IOException {
        Path temporary = Files.createTempFile(directory, ".review-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                 BufferedWriter writer = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
                writer.write(HEADER); writer.newLine();
                for (Progress item : schedules.values().stream().sorted(Comparator.comparing(value -> key(value.word()))).toList()) {
                    writer.write(String.join("\t", "P", encode(item.word()), Integer.toString(item.level()),
                            Long.toString(item.dueAt()), Long.toString(item.lastReviewedAt()))); writer.newLine();
                }
                if (savedRound != null) {
                    writer.write(String.join("\t", "R", Integer.toString(savedRound.position()), Integer.toString(savedRound.remembered()),
                            Integer.toString(savedRound.forgotten()), Integer.toString(savedRound.skipped()),
                            Long.toString(savedRound.startedAt()), Long.toString(savedRound.completedAt()))); writer.newLine();
                    for (String word : savedRound.words()) { writer.write("Q\t" + encode(word)); writer.newLine(); }
                }
                writer.flush(); channel.force(true);
                if (channel.size() > MAX_FILE_BYTES) throw new IOException("复习记录过大，原进度已保留");
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { throw new IOException("数据位置不支持安全替换复习进度，原文件已保留", ex); }
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static String key(String word) { return word.toLowerCase(Locale.ROOT); }
    private static String validWord(String word) {
        if (word == null || word.isBlank() || !word.equals(word.strip()) || word.length() > 120
                || word.codePoints().anyMatch(value -> Character.isISOControl(value) || value == 0x2028 || value == 0x2029)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(word)) throw new IllegalArgumentException("Invalid review word");
        return word;
    }
    private static String encode(String word) { return Base64.getEncoder().encodeToString(word.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) throws CharacterCodingException { return decodeUtf8(Base64.getDecoder().decode(value)); }
    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
