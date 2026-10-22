package wordnest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

/** Headless data and corruption tests; optional --full verifies real classpath resources. */
public final class LearningRepositoryTest {
    private static final int WNL1 = 0x574e4c31;
    private static final int WNE1 = 0x574e4531;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        testEmpty();
        testLookupAndGrouping();
        testSpellingAliases();
        testShuffledRecords();
        testBinaryValidation();
        testGzipValidation();
        testTsvValidation();
        testResourceLimits();
        testStreamOwnership();
        if (Arrays.asList(args).contains("--full")) testBundledResources();
        System.out.println("PASS: " + assertions + " learning repository assertions");
    }

    private static void testEmpty() {
        LearningRepository repository = LearningRepository.empty();
        check(repository.lexicalWords() == 0 && repository.originWords() == 0 && repository.curatedWords() == 0,
                "empty coverage");
        check(repository.senses(null).isEmpty() && repository.origins(" ").isEmpty()
                && repository.curated("missing").isEmpty(), "empty lookups");
    }

    private static void testSpellingAliases() throws Exception {
        LearningRepository alias = load(List.of(), List.of(),
                "example\torganise\tOrganise it.\t组织它。\t原创\n");
        check(alias.curated("organize").equals(alias.curated("organise")), "US spelling resolves curated UK card");
        check(alias.curated(" ORGANIZE ").equals(alias.curated("organise")), "alias normalization");
        check(alias.curatedWords() == 1, "alias is not counted as a second authored card");
        LearningRepository exact = load(List.of(), List.of(),
                "example\torganise\tUK example.\t英式。\t原创\n"
                + "example\torganize\tUS example.\t美式。\t原创\n");
        check(exact.curated("organize").get(0).first().equals("US example."), "exact card wins over alias");
        check(alias.senses("organize").isEmpty(), "curated alias does not replace lexical source lookup");
    }

    private static void testLookupAndGrouping() throws Exception {
        String[] first = lexical("Research", "sense-1", "A definition.\nSecond line.");
        first[4] = "First example.\tAuthor Name\nSecond example.\tOpen English WordNet / Princeton WordNet";
        first[5] = "enquiry\ninquiry";
        first[6] = "researcher\nresearch";
        String[] second = lexical("RESEARCH", "sense-2", "Second sense");
        second[2] = "v";
        second[4] = "Legacy example without an attribution";
        List<String[]> originRows = List.of(
                new String[]{"research", "derived_from", "recherche", "French", "https://example.test/research"},
                new String[]{"RESEARCH", "related_to", "search", "English", ""});
        String curated = "\ufeff# Local material\r\nkind\tword\tfirst\tsecond\tthird\r\n"
                + "example\tResearch\tA local example.\t本地译文\t原创\r\n"
                + "root_related\tresearch\tsearch\t查找\t说明\r\n"
                + "source\titem\tTitle\thttps://example.test\t";
        LearningRepository repository = load(List.of(first, second, lexical("item", "sense-i", "entry")), originRows, curated);
        check(repository.lexicalWords() == 2, "unique lexical words, not sense count");
        check(repository.originWords() == 1 && repository.curatedWords() == 2, "independent unique word counts");
        List<LearningRepository.LexicalSense> senses = repository.senses(" RESEARCH ");
        check(senses.size() == 2 && senses.get(0).id().equals("sense-1")
                && senses.get(1).id().equals("sense-2"), "all senses keep source order");
        check(senses.get(0).definition().contains("\nSecond line."), "definition newlines preserved");
        check(senses.get(0).examples().get(0).equals(new LearningRepository.Example("First example.", "Author Name")),
                "example text and source separated");
        check(senses.get(1).examples().get(0).source().equals("Open English WordNet / Princeton WordNet"),
                "old unattributed example receives explicit dataset source");
        check(senses.get(0).synonyms().equals(List.of("enquiry", "inquiry")), "synonyms retain sense grouping");
        check(senses.get(0).derivations().equals(List.of("researcher", "research")), "derivation list preserved");
        check(repository.origins("research").size() == 2 && repository.origins("research").get(1).sourceUrl().isEmpty(),
                "all origins retained, source URL may be empty");
        check(repository.origins("research").get(0).language().equals("French"), "origin language");
        check(repository.curated("research").get(0).second().equals("本地译文"), "UTF-8 curated content");
        check(repository.curated("research").get(1).kind().equals("root_related"), "curated kinds preserved");
        check(repository.senses("unknown").isEmpty() && repository.origins(null).isEmpty()
                && repository.curated(" ").isEmpty(), "missing/null/blank queries");
        expectUnsupported(() -> senses.clear(), "sense list immutable");
        expectUnsupported(() -> senses.get(0).examples().clear(), "nested examples immutable");
        expectUnsupported(() -> senses.get(0).synonyms().add("mutated"), "nested synonyms immutable");
        expectUnsupported(() -> repository.origins("research").clear(), "origin list immutable");
        check(repository.senses("research").get(0).synonyms().size() == 2, "lookups cannot mutate backing data");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            check(repository.senses("ITEM").size() == 1 && repository.curated("ITEM").size() == 1,
                    "normalization independent of Turkish system locale");
        } finally {
            Locale.setDefault(previous);
        }
    }

    private static void testShuffledRecords() throws Exception {
        List<String[]> records = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            records.add(lexical(String.format(Locale.ROOT, "word%04d", i), "sense-" + i, "definition-" + i));
        }
        Collections.shuffle(records, new Random(15));
        records.add(lexical("WORD0007", "additional", "second seven"));
        LearningRepository repository = load(records, List.of(), "");
        check(repository.lexicalWords() == 400, "shuffled word coverage");
        for (int i = 0; i < 400; i++) {
            List<LearningRepository.LexicalSense> result = repository.senses(String.format(Locale.ROOT, "word%04d", i));
            check(result.get(0).definition().equals("definition-" + i), "index offsets follow shuffled record " + i);
        }
        check(repository.senses("word0007").size() == 2
                && repository.senses("word0007").get(1).id().equals("additional"), "duplicate keys retained in source order");
    }

    private static void testBinaryValidation() throws Exception {
        expectIOException(() -> index(gzip(out -> { out.writeInt(0); out.writeInt(0); })), "wrong magic");
        expectIOException(() -> index(gzip(out -> { out.writeInt(WNL1); out.writeInt(-1); })), "negative count");
        expectIOException(() -> index(gzip(out -> { out.writeInt(WNL1); out.writeInt(2_000_001); })), "excess count");
        expectIOException(() -> index(gzip(out -> { out.writeInt(WNL1); out.writeInt(1); })), "count larger than available records");
        expectIOException(() -> index(gzip(out -> { out.writeInt(WNL1); out.writeInt(0); out.writeByte(0); })), "decompressed tail");
        expectIOException(() -> index(gzip(out -> { out.writeInt(WNL1); out.writeInt(1); out.writeInt(-1); out.write(new byte[40]); })),
                "negative field length");
        expectIOException(() -> index(gzip(out -> {
            out.writeInt(WNL1); out.writeInt(1); out.writeInt(1_048_577); out.write(new byte[40]);
        })), "field over 1 MiB");
        expectIOException(() -> index(gzip(out -> {
            out.writeInt(WNL1); out.writeInt(1); out.writeInt(1000); out.write(new byte[40]);
        })), "truncated field body");
        expectIOException(() -> index(binary(WNL1, List.<String[]>of(lexical("  ", "id", "definition")))), "blank headword");
        List<byte[]> malformed = List.of(new byte[]{(byte) 0xff}, new byte[]{(byte) 0xc0, (byte) 0xaf},
                new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                new byte[]{(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80}, new byte[]{(byte) 0xe2, (byte) 0x82});
        for (byte[] invalid : malformed) {
            expectIOException(() -> index(gzip(out -> {
                out.writeInt(WNL1); out.writeInt(1);
                for (int field = 0; field < 7; field++) {
                    byte[] value = field == 3 ? invalid : (field == 0 ? "word" : "valid").getBytes(StandardCharsets.UTF_8);
                    out.writeInt(value.length); out.write(value);
                }
            })), "strict UTF-8 in a non-key field " + Arrays.toString(invalid));
        }
        String[] maximum = lexical("maximum", "id", "a".repeat(IndexedLearningData.MAX_FIELD_BYTES));
        LearningRepository repository = load(List.<String[]>of(maximum), List.of(), "");
        check(repository.senses("maximum").get(0).definition().length() == IndexedLearningData.MAX_FIELD_BYTES,
                "exact 1 MiB field accepted");
    }

    private static void testGzipValidation() throws Exception {
        byte[] compressed = binary(WNL1, List.<String[]>of(lexical("word", "id", "definition")));
        byte[] crc = compressed.clone(); crc[crc.length - 8] ^= 1;
        expectIOException(() -> index(crc), "gzip CRC mismatch");
        byte[] size = compressed.clone(); size[size.length - 4] ^= 1;
        expectIOException(() -> index(size), "gzip expanded-size mismatch");
        expectIOException(() -> index(Arrays.copyOf(compressed, compressed.length - 1)), "gzip truncated trailer");
        expectIOException(() -> index(Arrays.copyOf(compressed, 15)), "gzip truncated deflate stream");
        byte[] trailing = Arrays.copyOf(compressed, compressed.length + 1);
        expectIOException(() -> index(trailing), "physical trailing garbage rejected");
        byte[] concatenated = new byte[compressed.length * 2];
        System.arraycopy(compressed, 0, concatenated, 0, compressed.length);
        System.arraycopy(compressed, 0, concatenated, compressed.length, compressed.length);
        expectIOException(() -> index(concatenated), "additional gzip member rejected");
        byte[] flags = compressed.clone(); flags[3] = (byte) 0xe0;
        expectIOException(() -> index(flags), "reserved gzip flags");
        byte[] extended = extendedHeader(compressed);
        check(index(extended).words() == 1, "valid optional gzip fields and header CRC");
        extended[25] ^= 1;
        expectIOException(() -> index(extended), "bad optional gzip header CRC");
    }

    private static void testTsvValidation() throws Exception {
        StringBuilder allKinds = new StringBuilder();
        for (String kind : List.of("example", "collocation", "derived", "synonym", "etymology", "morphology", "source", "root_related")) {
            allKinds.append(kind).append("\tword\tfirst\tsecond\tthird\n");
        }
        check(load(List.of(), List.of(), allKinds.toString()).curated("word").size() == 8, "all curated kinds supported");
        expectIOException(() -> tsv("unknown\tword\ta\tb\tc"), "unknown curated kind");
        expectIOException(() -> tsv("example\tword\ta\tb"), "missing TSV column");
        expectIOException(() -> tsv("example\tword\ta\tb\tc\td"), "extra TSV column");
        expectIOException(() -> tsv("example\t \ta\tb\tc"), "empty TSV key");
        expectIOException(() -> tsv("example\tword\t" + "x".repeat(1_048_577) + "\tb\tc"), "overlong TSV field");
        expectIOException(() -> IndexedLearningData.loadTsv(new ByteArrayInputStream(new byte[]{(byte) 0xff})), "invalid TSV UTF-8");
        check(tsv("#comment\n\n").words() == 0, "comment-only TSV accepted");
    }

    private static void testResourceLimits() throws Exception {
        byte[] empty = binary(WNL1, List.of());
        check(IndexedLearningData.loadGzip(new ByteArrayInputStream(empty), WNL1, 7, 8).words() == 0,
                "exact decompressed resource size accepted");
        expectIOException(() -> IndexedLearningData.loadGzip(new ByteArrayInputStream(empty), WNL1, 7, 7),
                "expanded resource size limit enforced");
        expectIOException(() -> IndexedLearningData.loadTsv(new ByteArrayInputStream(new byte[]{'#', 'x'}), 1),
                "TSV resource size limit enforced");
        check(IndexedLearningData.MAX_RESOURCE_BYTES == 192 * 1024 * 1024, "production resource cap is 192 MiB");
    }

    private static void testStreamOwnership() throws Exception {
        TrackedInput lexical = new TrackedInput(new byte[]{0});
        TrackedInput origin = new TrackedInput(binary(WNE1, List.of()));
        TrackedInput curated = new TrackedInput(new byte[0]);
        expectIOException(() -> LearningRepository.load(lexical, origin, curated), "earlier stream failure");
        check(lexical.closed && origin.closed && curated.closed, "all supplied streams closed on failure");
    }

    private static void testBundledResources() throws Exception {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long started = System.nanoTime();
        LearningRepository repository = LearningRepository.load();
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.gc();
        long retained = runtime.totalMemory() - runtime.freeMemory() - before;
        check(repository.lexicalWords() >= 120_000, "real lexical coverage");
        check(repository.originWords() >= 100_000, "real etymology coverage");
        for (String word : List.of("relevant", "research", "analysis", "derive")) {
            List<LearningRepository.LexicalSense> senses = repository.senses(word);
            check(!senses.isEmpty(), "real lexical word " + word);
            check(senses.stream().anyMatch(sense -> !sense.examples().isEmpty()), "real examples " + word);
            check(senses.stream().anyMatch(sense -> !sense.derivations().isEmpty()), "real derivations " + word);
            for (var sense : senses) for (var example : sense.examples()) {
                check(!example.text().isBlank() && !example.source().isBlank(), "real example attribution " + word);
                check(!example.text().contains("\t"), "example attribution separated " + word);
            }
        }
        check(repository.senses("relevant").get(0).derivations().contains("relevance"), "relevant has verified derivation");
        check(repository.senses("relevant").get(0).synonyms().isEmpty(), "missing source synonym is not invented");
        System.out.printf(Locale.ROOT, "Full learning data: %,d lexical words / %,d origin words / %,d curated words; %.3f s; approximate retained heap %.1f MiB%n",
                repository.lexicalWords(), repository.originWords(), repository.curatedWords(), seconds, retained / 1048576.0);
    }

    private static byte[] extendedHeader(byte[] compressed) throws Exception {
        ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        prefix.write(new byte[]{0x1f, (byte) 0x8b, 8, 30, 0, 0, 0, 0, 0, 0});
        prefix.write(new byte[]{3, 0, 1, 2, 3});
        prefix.write("name\0comment\0".getBytes(StandardCharsets.US_ASCII));
        CRC32 crc = new CRC32(); crc.update(prefix.toByteArray());
        prefix.write((int) crc.getValue() & 0xff); prefix.write((int) (crc.getValue() >>> 8) & 0xff);
        prefix.write(compressed, 10, compressed.length - 10);
        return prefix.toByteArray();
    }

    private static LearningRepository load(List<String[]> lexical, List<String[]> origins, String curated) throws Exception {
        return LearningRepository.load(new ByteArrayInputStream(binary(WNL1, lexical)),
                new ByteArrayInputStream(binary(WNE1, origins)), new ByteArrayInputStream(curated.getBytes(StandardCharsets.UTF_8)));
    }

    private static String[] lexical(String word, String id, String definition) {
        return new String[]{word, id, "n", definition, "", "", ""};
    }

    private static byte[] binary(int magic, List<String[]> rows) throws Exception {
        return gzip(output -> {
            output.writeInt(magic); output.writeInt(rows.size());
            for (String[] row : rows) for (String field : row) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                output.writeInt(bytes.length); output.write(bytes);
            }
        });
    }

    private static byte[] gzip(Writer writer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) { writer.write(output); }
        return bytes.toByteArray();
    }

    private static IndexedLearningData index(byte[] bytes) throws IOException {
        return IndexedLearningData.loadGzip(new ByteArrayInputStream(bytes), WNL1, 7);
    }

    private static IndexedLearningData tsv(String value) throws IOException {
        return IndexedLearningData.loadTsv(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void expectIOException(Action action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { assertions++; return; }
        throw new AssertionError("Expected IOException: " + message);
    }

    private static void expectUnsupported(Action action, String message) throws Exception {
        try { action.run(); } catch (UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError("Expected immutable result: " + message);
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        boolean closed;
        TrackedInput(byte[] data) { super(data); }
        @Override public void close() { closed = true; }
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
    @FunctionalInterface private interface Writer { void write(DataOutputStream output) throws Exception; }
}
