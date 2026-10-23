package wordnest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/** Run with plain javac/java; no test framework or network access is required. */
public final class CoreTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        testDictionary();
        testUnsortedDictionary();
        testCorruptDictionaries();
        Path temporaryRoot = args.length == 0
                ? Files.createTempDirectory("wordnest-core-test-")
                : Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "wordnest-core-test-");
        try {
            testFavorites(temporaryRoot.resolve("favorites"));
            testCorruptFavorites(temporaryRoot.resolve("corrupt"));
            testFailedSave(temporaryRoot.resolve("unwritable"));
        } finally {
            try (var paths = Files.walk(temporaryRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        if (args.length > 1) {
            testFullDictionary(Path.of(args[1]));
        }
        System.out.println("PASS: " + assertions + " core assertions");
    }

    private static void testUnsortedDictionary() throws Exception {
        List<WordEntry> words = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            words.add(entry(String.format(Locale.ROOT, "word%04d", i), "词义 " + i));
        }
        Collections.shuffle(words, new Random(17));
        words.add(entry("WORD0007", "重复词不能覆盖首次记录"));
        DictionaryRepository repository = DictionaryRepository.load(new ByteArrayInputStream(
                dictionary(words.toArray(WordEntry[]::new))));
        check(repository.size() == 1_000, "capitalization duplicates share one index entry");
        for (int i = 0; i < 1_000; i++) {
            String word = String.format(Locale.ROOT, "word%04d", i);
            check(repository.lookup(word).orElseThrow().translation().equals("词义 " + i),
                    "sorted offset matches original record " + i);
        }
        check(repository.suggest("word000", 100).equals(List.of("word0000", "word0001", "word0002",
                "word0003", "word0004", "word0005", "word0006", "word0007", "word0008", "word0009")),
                "suggestions stop at prefix boundary");
    }

    private static void testFullDictionary(Path dictionaryFile) throws Exception {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long started = System.nanoTime();
        DictionaryRepository repository = DictionaryRepository.load(Files.newInputStream(dictionaryFile));
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.gc();
        long retained = runtime.totalMemory() - runtime.freeMemory() - before;
        check(repository.size() >= 100_000, "full dictionary has substantial coverage");
        check(!repository.lookup("computer").orElseThrow().translation().isBlank(), "computer has Chinese translation");
        check(repository.lookup(" RESEARCH ").isPresent(), "research found in complete dictionary");
        check(!repository.suggest("exper", 8).isEmpty(), "full dictionary prefix suggestions");
        System.out.printf(Locale.ROOT, "Full dictionary: %,d words; %.3f s load; approximate retained heap %.1f MiB%n",
                repository.size(), seconds, retained / 1048576.0);
    }

    private static void testDictionary() throws Exception {
        WordEntry apple = entry("apple", "苹果\n苹果树");
        WordEntry apply = entry("apply", "申请；应用");
        WordEntry item = entry("item", "项目");
        DictionaryRepository repository = DictionaryRepository.load(new ByteArrayInputStream(
                dictionary(apple, apply, entry("banana", "香蕉"), item)));
        check(repository.size() == 4, "dictionary size");
        check(repository.lookup(" APPLE ").orElseThrow().equals(apple), "trimmed, case-insensitive lookup");
        check(repository.lookup("missing").isEmpty(), "missing word");
        check(repository.lookup(null).isEmpty(), "null lookup is empty");
        check(repository.lookup("apple").orElseThrow().translation().contains("\n"), "real newlines preserved");
        check(repository.suggest(" AP ", 10).equals(List.of("apple", "apply")), "normalized prefix order");
        check(repository.suggest("ap", 1).equals(List.of("apple")), "suggestion limit");
        check(repository.suggest("ap", 0).isEmpty(), "zero limit");
        check(repository.suggest("", 3).isEmpty(), "blank prefix");
        check(repository.suggest("zzz", 10).isEmpty(), "missing prefix");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            check(repository.lookup("ITEM").orElseThrow().equals(item), "lookup independent of system locale");
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static void testCorruptDictionaries() throws Exception {
        expectIOException(() -> loadRaw(data -> { data.writeInt(0); data.writeInt(0); }), "bad magic");
        expectIOException(() -> loadRaw(data -> { data.writeInt(0x574e4431); data.writeInt(-1); }), "negative count");
        expectIOException(() -> loadRaw(data -> { data.writeInt(0x574e4431); data.writeInt(2_000_001); }), "excessive count");
        expectIOException(() -> loadRaw(data -> {
            data.writeInt(0x574e4431); data.writeInt(1); data.writeInt(-1);
        }), "negative field length");
        expectIOException(() -> loadRaw(data -> {
            data.writeInt(0x574e4431); data.writeInt(1); data.writeInt(1_048_577);
        }), "excessive field length");
        expectIOException(() -> loadRaw(data -> {
            data.writeInt(0x574e4431); data.writeInt(1); data.writeInt(100); data.writeByte(1);
        }), "truncated field");
        expectIOException(() -> loadRaw(data -> {
            data.writeInt(0x574e4431); data.writeInt(1); data.writeInt(1); data.writeByte(0xff);
        }), "invalid UTF-8");
        expectIOException(() -> loadRaw(data -> {
            data.writeInt(0x574e4431); data.writeInt(0); data.writeByte(1);
        }), "trailing data");
        byte[] compressed = dictionary(entry("word", "单词"));
        compressed[compressed.length - 8] ^= 1;
        expectIOException(() -> DictionaryRepository.load(new ByteArrayInputStream(compressed)), "bad gzip checksum");
        byte[] truncated = Arrays.copyOf(dictionary(entry("word", "单词")), 12);
        expectIOException(() -> DictionaryRepository.load(new ByteArrayInputStream(truncated)), "truncated gzip");
    }

    private static void testFavorites(Path directory) throws Exception {
        FavoritesStore store = new FavoritesStore(directory);
        check(store.list().isEmpty(), "new favorites is empty");
        WordEntry hello = entry("Hello", "你好\n问候");
        String note = "第一次看到这个单词。\n例句：Hello, world!\t中文、emoji：☀\r\n最后一行\\n";
        store.save(hello, note);
        store.save(entry("world", "世界"), "第二条");
        check(store.contains(" HELLO "), "case-insensitive favorite membership");
        check(store.list().get(0).entry().word().equals("world"), "newest first");
        FavoritesStore reopened = new FavoritesStore(directory);
        check(reopened.list().size() == 2, "favorites survives restart");
        SavedWord saved = reopened.get("hello").orElseThrow();
        check(saved.entry().equals(hello), "all dictionary fields survive restart");
        check(saved.note().equals(note), "Chinese tabs and newlines survive restart");
        check(saved.savedAt() > 0, "timestamp survives restart");
        reopened.save(hello, "修改后的笔记\n第二行");
        check(reopened.list().size() == 2, "editing does not duplicate");
        check(reopened.list().get(0).entry().word().equals("Hello"), "editing becomes newest");
        reopened.remove(" HeLLo ");
        FavoritesStore afterRemoval = new FavoritesStore(directory);
        check(!afterRemoval.contains("hello"), "removal survives restart");
        check(afterRemoval.get("hello").orElseThrow().note().equals("修改后的笔记\n第二行"),
                "cancelled favorite retains its personal note after restart");
        check(afterRemoval.list().size() == 1, "other favorite preserved");
        afterRemoval.remove("missing");
        check(afterRemoval.list().size() == 1, "missing removal is harmless");
        afterRemoval.remove("world");
        check(new FavoritesStore(directory).list().isEmpty(), "empty saved store reloads");
    }

    private static void testCorruptFavorites(Path directory) throws Exception {
        Files.createDirectories(directory);
        Path file = directory.resolve("favorites.tsv");
        byte[] broken = "WORDNEST-FAVORITES\t1\nnot\ta\tvalid\trow\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, broken);
        expectIOException(() -> new FavoritesStore(directory), "corrupt favorites rejected");
        check(Arrays.equals(Files.readAllBytes(file), broken), "corrupt favorites never overwritten");
        Files.writeString(file, "");
        expectIOException(() -> new FavoritesStore(directory), "existing empty file is corrupt, not silently reset");
        String row = "aGVsbG8=\t\t\t\t\t\t!!!\t1\n";
        Files.writeString(file, "WORDNEST-FAVORITES\t1\n" + row);
        expectIOException(() -> new FavoritesStore(directory), "invalid Base64 rejected");
    }

    private static void testFailedSave(Path directory) throws Exception {
        FavoritesStore store = new FavoritesStore(directory);
        Path target = Files.createDirectory(directory.resolve("favorites.tsv"));
        Path marker = target.resolve("do-not-touch.txt");
        Files.writeString(marker, "preserve");
        expectIOException(() -> store.save(entry("apple", "苹果"), "note"), "failed atomic replacement reported");
        check(store.list().isEmpty(), "failed save leaves in-memory state unchanged");
        check(Files.readString(marker).equals("preserve"), "failed save leaves existing target unchanged");
        try (var children = Files.list(directory)) {
            check(children.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "failed save cleans temporary file");
        }
    }

    private static WordEntry entry(String word, String translation) {
        return new WordEntry(word, "həˈləʊ", translation, "An English definition.\nSecond meaning.", "ielts cet4", "s:words");
    }

    private static byte[] dictionary(WordEntry... entries) throws IOException {
        return gzip(data -> {
            data.writeInt(0x574e4431);
            data.writeInt(entries.length);
            for (WordEntry entry : entries) {
                for (String field : List.of(entry.word(), entry.phonetic(), entry.translation(),
                        entry.definition(), entry.tags(), entry.exchange())) {
                    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                    data.writeInt(bytes.length);
                    data.write(bytes);
                }
            }
        });
    }

    private static byte[] gzip(DataAction action) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(bytes))) {
            action.run(data);
        }
        return bytes.toByteArray();
    }

    private static void loadRaw(DataAction action) throws IOException {
        DictionaryRepository.load(new ByteArrayInputStream(gzip(action)));
    }

    private static void expectIOException(CheckedAction action, String label) throws Exception {
        try {
            action.run();
        } catch (IOException expected) {
            assertions++;
            return;
        }
        throw new AssertionError("Expected IOException: " + label);
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    @FunctionalInterface private interface DataAction { void run(DataOutputStream data) throws IOException; }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
