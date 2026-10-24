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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The latest 50 or 100 distinct successful lookups, plus independent session navigation.
 * Callers supply the dictionary's canonical headword, after saving any edited notes.
 * A persistence failure never changes either in-memory list or the session position.
 */
public final class LookupHistory {
    private static final String HEADER = "WORDNEST-HISTORY\t1";
    private static final int MAX_LIMIT = 100;
    private int limit = MAX_LIMIT;
    private static final int MAX_WORD_LENGTH = 120;
    private static final int MAX_FILE_BYTES = 64 * 1024;
    private final Path directory;
    private final Path file;
    private List<String> recent;
    private List<String> session = List.of();
    private int position = -1;

    /**
     * Loads history without resuming a prior navigation session or writing a file.
     * Load this separately from personal vocabulary: an invalid file is preserved
     * and reported with IOException, so the UI can offer sessionOnly() as fallback.
     */
    public LookupHistory(Path directory) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.file = this.directory.resolve("history.tsv");
        Files.createDirectories(this.directory);
        this.recent = load();
    }

    private LookupHistory() {
        directory = null;
        file = null;
        recent = List.of();
    }

    /** Explicit degraded mode: recent lookups and navigation exist only in memory. */
    public static LookupHistory sessionOnly() {
        return new LookupHistory();
    }

    /** Immutable, newest-first snapshot. Canonical display spelling is preserved. */
    public synchronized List<String> recent() {
        return recent;
    }

    /**
     * Starts the session at the displayed startup word without counting a new lookup.
     * Loading recent().get(0), or a fallback word, must use this instead of record().
     */
    public synchronized void seedSession(String word) {
        session = List.of(validateWord(word));
        position = 0;
    }

    /**
     * Records a successful new query. Revisits move to the front of recent history;
     * a query after going back discards the session's forward branch. A consecutive
     * repeat keeps the existing navigation position and does not discard that branch.
     */
    public synchronized void record(String word) throws IOException {
        String canonical = validateWord(word);
        List<String> updated = new ArrayList<>(limit);
        updated.add(canonical);
        for (String prior : recent) {
            if (!key(prior).equals(key(canonical)) && updated.size() < limit) updated.add(prior);
        }
        List<String> snapshot = List.copyOf(updated);
        // Skip needless disk writes, including a repeated query of the latest word.
        if (!snapshot.equals(recent)) persist(snapshot);
        recent = snapshot;
        appendSession(canonical);
    }

    /**
     * UI recovery after record() fails: keep navigation aligned with a successfully
     * displayed word, without changing saved/recent history or attempting another write.
     */
    public synchronized void recordSession(String word) {
        appendSession(validateWord(word));
    }

    public synchronized Optional<String> current() {
        return position < 0 ? Optional.empty() : Optional.of(session.get(position));
    }

    public synchronized boolean canBack() {
        return position > 0;
    }

    public synchronized boolean canForward() {
        return position >= 0 && position + 1 < session.size();
    }

    /** Inspect before saving notes and resolving this headword in the dictionary. */
    public synchronized Optional<String> peekBack() {
        return canBack() ? Optional.of(session.get(position - 1)) : Optional.empty();
    }

    /** Inspect before saving notes and resolving this headword in the dictionary. */
    public synchronized Optional<String> peekForward() {
        return canForward() ? Optional.of(session.get(position + 1)) : Optional.empty();
    }

    /** Commit only after the target can be displayed. Navigation is not a new query. */
    public synchronized boolean commitBack(String expectedWord) {
        if (!canBack() || !matches(session.get(position - 1), expectedWord)) return false;
        position--;
        return true;
    }

    /** Commit only after the target can be displayed. A stale/absent target is a no-op. */
    public synchronized boolean commitForward(String expectedWord) {
        if (!canForward() || !matches(session.get(position + 1), expectedWord)) return false;
        position++;
        return true;
    }

    /** Clears persistent recent lookups and this session only after a successful write. */
    public synchronized void clear() throws IOException {
        persist(List.of());
        recent = List.of();
        session = List.of();
        position = -1;
    }

    private void appendSession(String canonical) {
        if (position >= 0 && key(session.get(position)).equals(key(canonical))) return;
        List<String> updated = new ArrayList<>(session.subList(0, position + 1));
        updated.add(canonical);
        session = List.copyOf(updated);
        position = session.size() - 1;
    }

    private List<String> load() throws IOException {
        if (Files.notExists(file)) return List.of();
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            // Bound reads even if another process extends the file after it is opened.
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) throw invalid("文件过大", null);
        List<String> lines;
        try {
            lines = decodeUtf8(bytes).lines().toList();
        } catch (CharacterCodingException ex) {
            throw invalid("不是有效的 UTF-8 文本", ex);
        }
        if (lines.isEmpty()) throw invalid("文件格式不正确", null);
        if (HEADER.equals(lines.get(0))) limit = 100;
        else if ("WORDNEST-HISTORY\t2\t50".equals(lines.get(0))) limit = 50;
        else throw invalid("文件格式不正确", null);
        if (lines.size() > limit + 1) throw invalid("记录超过 " + limit + " 条", null);
        List<String> loaded = new ArrayList<>();
        HashSet<String> keys = new HashSet<>();
        for (int index = 1; index < lines.size(); index++) {
            try {
                String decoded = decodeUtf8(Base64.getDecoder().decode(lines.get(index)));
                String word = validateWord(decoded);
                if (!word.equals(decoded) || !keys.add(key(word))) {
                    throw new IllegalArgumentException("Noncanonical or duplicate word");
                }
                loaded.add(word);
            } catch (IllegalArgumentException | CharacterCodingException ex) {
                throw invalid("第 " + (index + 1) + " 行损坏", ex);
            }
        }
        return List.copyOf(loaded);
    }

    private IOException invalid(String reason, Exception cause) {
        return new IOException("查词历史" + reason + "，原文件已保留：" + file, cause);
    }

    public synchronized int limit() { return limit; }

    /** Save the preference and trimmed records together; failures preserve all state. */
    public synchronized void setLimit(int requested) throws IOException {
        if (requested != 50 && requested != 100) throw new IllegalArgumentException("Expected 50 or 100");
        if (requested == limit) return;
        List<String> trimmed = List.copyOf(recent.subList(0, Math.min(requested, recent.size())));
        persist(trimmed, requested);
        recent = trimmed;
        limit = requested;
    }

    private void persist(List<String> snapshot) throws IOException {
        persist(snapshot, limit);
    }

    private void persist(List<String> snapshot, int savedLimit) throws IOException {
        if (file == null) return;
        Path temporary = Files.createTempFile(directory, ".history-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 BufferedWriter writer = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
                writer.write(savedLimit == 100 ? HEADER : "WORDNEST-HISTORY\t2\t50");
                writer.newLine();
                for (String word : snapshot) {
                    writer.write(Base64.getEncoder().encodeToString(word.getBytes(StandardCharsets.UTF_8)));
                    writer.newLine();
                }
                writer.flush();
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                throw new IOException("这个存储位置不支持安全替换查词历史，原文件已保留。", ex);
            }
            // A successful move consumes the temporary file. Do not perform a fallible
            // cleanup after commit, which could falsely report that the save failed.
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static String validateWord(String word) {
        if (word == null) throw new IllegalArgumentException("History word must not be null");
        String canonical = word.strip();
        if (canonical.isEmpty() || canonical.length() > MAX_WORD_LENGTH
                || canonical.codePoints().anyMatch(value -> Character.isISOControl(value)
                || value == 0x2028 || value == 0x2029)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(canonical)) {
            throw new IllegalArgumentException("History word must be a valid headword of at most 120 characters");
        }
        return canonical;
    }

    private static boolean matches(String actual, String expected) {
        return expected != null && key(actual).equals(key(expected.strip()));
    }

    private static String key(String word) {
        return word.toLowerCase(Locale.ROOT);
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
