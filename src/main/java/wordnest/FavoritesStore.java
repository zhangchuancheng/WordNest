package wordnest;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Persistent personal vocabulary. A failed write never updates the in-memory snapshot. */
public final class FavoritesStore {
    private static final String LEGACY_HEADER = "WORDNEST-FAVORITES\t1";
    private static final String HEADER = "WORDNEST-FAVORITES\t2";
    private static final Comparator<SavedWord> RECENT_FIRST =
            Comparator.comparingLong(SavedWord::savedAt).reversed()
                    .thenComparing(item -> normalize(item.entry().word()));
    private final Path directory;
    private final Path file;
    private Map<String, SavedWord> entries;
    private boolean legacyFormat;

    public FavoritesStore(Path directory) throws IOException {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.file = this.directory.resolve("favorites.tsv");
        Files.createDirectories(this.directory);
        this.entries = load();
    }

    /** An immutable snapshot of favorites, ordered by last change time, newest first. */
    public synchronized List<SavedWord> list() {
        return entries.values().stream().filter(SavedWord::favorite).sorted(RECENT_FIRST).toList();
    }

    /** Removed favorites retain their notes and can be restored, including after a restart. */
    public synchronized List<SavedWord> listUnfavorited() {
        return entries.values().stream().filter(item -> !item.favorite()).sorted(RECENT_FIRST).toList();
    }

    public synchronized boolean contains(String word) {
        SavedWord saved = entries.get(normalize(word));
        return saved != null && saved.favorite();
    }

    /** Returns retained notes whether or not the entry is currently a favorite. */
    public synchronized Optional<SavedWord> get(String word) {
        return Optional.ofNullable(entries.get(normalize(word)));
    }

    public synchronized void save(WordEntry entry, String note) throws IOException {
        Objects.requireNonNull(entry, "entry");
        SavedWord previous = entries.get(normalize(entry.word()));
        setFavorite(entry, note, previous == null || previous.favorite());
    }

    /** Saves the current note and explicitly changes membership in the same atomic write. */
    public synchronized void setFavorite(WordEntry entry, String note, boolean favorite) throws IOException {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(note, "note");
        String key = normalize(entry.word());
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Cannot save a blank word");
        }
        // Ensure deterministic ordering even when two saves occur in the same millisecond.
        long latest = entries.values().stream().mapToLong(SavedWord::savedAt).max().orElse(0L);
        long savedAt = Math.max(System.currentTimeMillis(), latest == Long.MAX_VALUE ? latest : latest + 1);
        Map<String, SavedWord> updated = new HashMap<>(entries);
        updated.put(key, new SavedWord(entry, note, savedAt, favorite));
        persist(updated);
        entries = updated;
    }

    /** Soft removal: the existing entry and note remain available through get/listUnfavorited. */
    public synchronized void remove(String word) throws IOException {
        SavedWord saved = entries.get(normalize(word));
        if (saved != null && saved.favorite()) setFavorite(saved.entry(), saved.note(), false);
    }

    /** Restores the latest retained note, never a stale copy captured by an Undo button. */
    public synchronized boolean restoreFavorite(String word) throws IOException {
        SavedWord saved = entries.get(normalize(word));
        if (saved == null || saved.favorite()) return false;
        setFavorite(saved.entry(), saved.note(), true);
        return true;
    }

    private Map<String, SavedWord> load() throws IOException {
        Map<String, SavedWord> loaded = new HashMap<>();
        if (Files.notExists(file)) {
            return loaded;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            boolean legacy = LEGACY_HEADER.equals(header);
            if (!legacy && !HEADER.equals(header)) {
                throw new IOException("生词本格式不正确，原文件已保留：" + file);
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String[] fields = line.split("\t", -1);
                if (fields.length != (legacy ? 8 : 9)) {
                    throw invalidLine(lineNumber, null);
                }
                try {
                    WordEntry entry = new WordEntry(decode(fields[0]), decode(fields[1]),
                            decode(fields[2]), decode(fields[3]), decode(fields[4]), decode(fields[5]));
                    String key = normalize(entry.word());
                    if (key.isEmpty() || loaded.containsKey(key)) {
                        throw new IllegalArgumentException("Blank or duplicate word");
                    }
                    if (!legacy && !fields[8].equals("true") && !fields[8].equals("false")) {
                        throw new IllegalArgumentException("Invalid favorite state");
                    }
                    SavedWord saved = new SavedWord(entry, decode(fields[6]), Long.parseLong(fields[7]),
                            legacy || fields[8].equals("true"));
                    loaded.put(key, saved);
                } catch (IllegalArgumentException | CharacterCodingException ex) {
                    throw invalidLine(lineNumber, ex);
                }
            }
            legacyFormat = legacy;
        }
        return loaded;
    }

    private IOException invalidLine(int line, Exception cause) {
        return new IOException("生词本第 " + line + " 行损坏，原文件已保留：" + file, cause);
    }

    private void persist(Map<String, SavedWord> snapshot) throws IOException {
        Path temporary = Files.createTempFile(directory, ".favorites-", ".tmp");
        IOException failure = null;
        boolean replaced = false;
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 BufferedWriter writer = new BufferedWriter(Channels.newWriter(channel, StandardCharsets.UTF_8))) {
                writer.write(HEADER);
                writer.newLine();
                for (SavedWord saved : snapshot.values().stream().sorted(RECENT_FIRST).toList()) {
                    WordEntry entry = saved.entry();
                    writer.write(String.join("\t", encode(entry.word()), encode(entry.phonetic()),
                            encode(entry.translation()), encode(entry.definition()), encode(entry.tags()),
                            encode(entry.exchange()), encode(saved.note()), Long.toString(saved.savedAt()),
                            Boolean.toString(saved.favorite())));
                    writer.newLine();
                }
                writer.flush();
                channel.force(true);
            }
            if (legacyFormat) preserveLegacyBackup();
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                // Do not risk a non-atomic overwrite of the user's only saved copy.
                throw new IOException("这个存储位置不支持安全替换生词本，请使用本机磁盘目录。原文件已保留。", ex);
            }
            replaced = true;
            legacyFormat = false;
        } catch (IOException ex) {
            failure = ex;
            throw ex;
        } finally {
            // A successful move consumes the temporary file. Do not report a later cleanup
            // failure after committing, which would leave the caller's memory snapshot stale.
            if (!replaced) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupError) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupError);
                    } else {
                        throw cleanupError;
                    }
                }
            }
        }
    }

    /** Keep the exact v1 bytes before the first replacement; never overwrite an older backup. */
    private void preserveLegacyBackup() throws IOException {
        Path backup = directory.resolve("favorites.tsv.v1-backup");
        for (;;) {
            FileChannel output;
            try {
                output = FileChannel.open(backup, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException exists) {
                if (Files.isRegularFile(backup) && Files.mismatch(file, backup) == -1) return;
                backup = directory.resolve("favorites.tsv.v1-backup-" + UUID.randomUUID());
                continue;
            }
            try (output) {
                try (var input = Files.newInputStream(file)) {
                    input.transferTo(Channels.newOutputStream(output));
                }
                output.force(true);
                return;
            } catch (IOException failure) {
                try { Files.deleteIfExists(backup); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw new IOException("无法保留旧版生词本备份，尚未升级或修改原文件。", failure);
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String encode(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String encoded) throws CharacterCodingException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
