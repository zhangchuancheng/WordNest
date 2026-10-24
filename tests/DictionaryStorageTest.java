package wordnest;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/** Headless fixtures only. A child JVM releases Windows mappings before the parent deletes its temp directory. */
public final class DictionaryStorageTest {
    private static int assertions;
    private static final int MAGIC = 0x574e4432;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--worker")) {
            Path root = Path.of(args[1]);
            exactAndThreadSafe(root.resolve("exact"));
            cacheVerification(root.resolve("cache"));
            malformed(root.resolve("malformed"));
            largeIndex(root.resolve("large"));
            System.out.println("PASS: " + assertions + " dictionary storage assertions (Java " + System.getProperty("java.version")
                    + ", temporary WND2 fixtures, no UI)");
            return;
        }
        Path root = Files.createTempDirectory("wordnest-dictionary-storage-");
        try {
            String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            Process worker = new ProcessBuilder(executable, "-Djava.awt.headless=true", "-Xmx96m", "-cp",
                    System.getProperty("java.class.path"), DictionaryStorageTest.class.getName(), "--worker", root.toString()).inheritIO().start();
            if (worker.waitFor() != 0) throw new AssertionError("Dictionary fixture worker failed");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void exactAndThreadSafe(Path root) throws Exception {
        List<String[]> entries = List.of(row("apple", "Apple", "苹果\n苹果树"), row("apply", "apply", "申请"),
                row("in spite of", "in spite of", "尽管"), row("item", "item", "项目"),
                row("\uD800\uDC00", "\uD800\uDC00", "补充平面"), row("\uE000", "\uE000", "基本平面"));
        Fixture fixture = fixture(root, raw(entries), entries.size());
        DictionaryRepository repository = DictionaryRepository.loadBundled(fixture.cache, fixture.source);
        check(repository.size() == 6, "WND2 count is read from the mapped header");
        check(repository.lookup(" APPLE ").orElseThrow().word().equals("Apple"), "exact lookup preserves source capitalization");
        check(repository.lookup("apple").orElseThrow().translation().equals("苹果\n苹果树"), "multiline UTF-8 fields are intact");
        check(repository.lookup("in spite of").isPresent() && repository.lookup("in  spite of").isEmpty(), "storage retains precise phrase semantics");
        check(repository.lookup(null).isEmpty() && repository.lookup("missing").isEmpty(), "missing and null lookups are empty");
        check(repository.suggest(" AP ", 8).equals(List.of("Apple", "apply")), "prefix lookup returns original spelling in key order");
        check(repository.suggest("ap", 1).equals(List.of("Apple")), "prefix limit is enforced");
        check(repository.suggest("ap", 0).isEmpty() && repository.suggest("", 3).isEmpty(), "zero limit and blank prefix return no rows");
        check(repository.lookup("\uD800\uDC00").isPresent() && repository.lookup("\uE000").isPresent(), "UTF-16 order works across supplementary and BMP keys");
        Locale previous = Locale.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); check(repository.lookup("ITEM").isPresent(), "key case folding does not depend on system locale"); }
        finally { Locale.setDefault(previous); }
        expectUnsupported(() -> repository.suggest("a", 2).add("changed"), "suggestion snapshots are immutable");
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int worker = 0; worker < 6; worker++) results.add(workers.submit(() -> {
                for (int i = 0; i < 500; i++) {
                    if (!repository.lookup(i % 2 == 0 ? "apple" : "ITEM").isPresent()
                            || !repository.suggest("ap", 2).equals(List.of("Apple", "apply"))) return false;
                }
                return true;
            }));
            for (Future<Boolean> result : results) check(result.get(), "parallel lookup cursors never interfere");
        } finally { workers.shutdownNow(); }
        Fixture empty = fixture(root.resolve("empty"), raw(List.of()), 0);
        DictionaryRepository noEntries = DictionaryRepository.loadBundled(empty.cache, empty.source);
        check(noEntries.size() == 0 && noEntries.lookup("a").isEmpty() && noEntries.suggest("a", 1).isEmpty(), "empty WND2 has a valid terminal offset");
    }

    private static void cacheVerification(Path root) throws Exception {
        Fixture fixture = fixture(root, raw(List.<String[]>of(row("apple", "Apple", "苹果"))), 1);
        DictionaryResources.Cached first = DictionaryResources.prepare(fixture.cache, fixture.source);
        check(Files.isRegularFile(first.file()) && Files.size(first.file()) == fixture.raw.length, "first extraction publishes a complete cache");
        fixture.source.opened.clear();
        check(DictionaryResources.prepare(fixture.cache, fixture.source).file().equals(first.file()), "verified cache is reused");
        check(fixture.source.opened.equals(List.of("/dictionary/manifest.properties")), "cache hit does not decompress resource parts again");
        byte[] corrupted = Files.readAllBytes(first.file()); corrupted[corrupted.length - 1] ^= 1;
        Files.write(first.file(), corrupted);
        fixture.source.opened.clear();
        DictionaryResources.Cached repaired = DictionaryResources.prepare(fixture.cache, fixture.source);
        check(Arrays.equals(Files.readAllBytes(repaired.file()), fixture.raw), "same-size corrupted cache is detected by SHA and rebuilt");
        check(fixture.source.opened.size() > 1, "same-size corruption actually reopens compressed parts");
        Files.write(repaired.file(), new byte[]{1});
        check(Arrays.equals(Files.readAllBytes(DictionaryResources.prepare(fixture.cache, fixture.source).file()), fixture.raw), "truncated cache is safely rebuilt");
        check(DictionaryRepository.loadBundled(fixture.cache, fixture.source).lookup("APPLE").isPresent(), "repaired file supports real mapped lookups");

        Fixture alternate = fixture(root.resolve("unavailable-primary"), raw(List.<String[]>of(row("one", "one", "一"))), 1);
        Path primary = alternate.cache.resolve("dictionary-" + hash(alternate.raw) + ".wnd2");
        Files.createDirectories(primary); Files.writeString(primary.resolve("blocker"), "do not destroy");
        DictionaryResources.Cached fallback = DictionaryResources.prepare(alternate.cache, alternate.source);
        check(!fallback.file().equals(primary) && Files.isRegularFile(fallback.file()), "unreplaceable primary uses a uniquely named verified cache");
        check(Files.readString(primary.resolve("blocker")).equals("do not destroy"), "fallback does not destroy the unavailable primary");
        alternate.source.opened.clear();
        check(DictionaryResources.prepare(alternate.cache, alternate.source).file().equals(fallback.file()), "alternate cache is reused on subsequent loads");
        check(alternate.source.opened.size() == 1, "alternate cache hit needs no resource decompression");
        check(DictionaryRepository.loadBundled(alternate.cache, alternate.source).lookup("one").isPresent(), "alternate cache supports mapped lookup");
    }

    private static void malformed(Path root) throws Exception {
        byte[] valid = raw(List.<String[]>of(row("apple", "apple", "苹果")));
        List<byte[]> invalid = new ArrayList<>();
        byte[] magic = valid.clone(); ByteBuffer.wrap(magic).putInt(0, 0); invalid.add(magic);
        byte[] offset = valid.clone(); ByteBuffer.wrap(offset).putInt(8, 1); invalid.add(offset);
        byte[] terminal = valid.clone(); ByteBuffer.wrap(terminal).putInt(12, terminal.length - 1); invalid.add(terminal);
        byte[] length = valid.clone(); ByteBuffer.wrap(length).putInt(16, -1); invalid.add(length);
        byte[] excessive = valid.clone(); ByteBuffer.wrap(excessive).putInt(16, 1_048_577); invalid.add(excessive);
        byte[] utf = valid.clone(); utf[20] = (byte) 0xff; invalid.add(utf);
        invalid.add(raw(List.<String[]>of(row("Apple", "Apple", "苹果"))));
        invalid.add(raw(List.<String[]>of(row("apple", "other", "不一致"))));
        invalid.add(raw(List.of(row("b", "b", "乙"), row("a", "a", "甲"))));
        invalid.add(raw(List.of(row("a", "a", "甲"), row("a", "A", "重复"))));
        invalid.add(raw(List.<String[]>of(row("", "", "空词头"))));
        invalid.add(Arrays.copyOf(valid, valid.length + 1));
        for (int i = 0; i < invalid.size(); i++) {
            int count = (i == 8 || i == 9) ? 2 : 1;
            Fixture fixture = fixture(root.resolve("raw-" + i), invalid.get(i), count);
            expectIo(() -> DictionaryRepository.loadBundled(fixture.cache, fixture.source), "malformed WND2 rejected " + i);
            assertNoPublished(fixture.cache);
        }
        for (int i = 0; i < 10; i++) {
            Fixture fixture = fixture(root.resolve("manifest-" + i), valid, 1);
            Properties properties = properties(fixture.resources.resolve("manifest.properties"));
            switch (i) {
                case 0 -> properties.setProperty("raw.sha256", "0".repeat(64));
                case 1 -> properties.setProperty("raw.bytes", Integer.toString(valid.length - 1));
                case 2 -> properties.setProperty("raw.bytes", Integer.toString(valid.length + 1));
                case 3 -> properties.setProperty("part.0.sha256", "0".repeat(64));
                case 4 -> properties.setProperty("part.0", "../outside.gz");
                case 5 -> properties.setProperty("entries", "2");
                case 6 -> properties.setProperty("raw.bytes", "2147483648");
                case 7 -> properties.setProperty("parts", "0");
                case 8 -> Files.delete(fixture.resources.resolve("part-001.gz"));
                case 9 -> properties.setProperty("part.2.sha256", "0".repeat(64));
            }
            save(properties, fixture.resources.resolve("manifest.properties"));
            expectIo(() -> DictionaryRepository.loadBundled(fixture.cache, fixture.source), "invalid resource or manifest rejected " + i);
            assertNoPublished(fixture.cache);
        }
        Fixture damagedGzip = fixture(root.resolve("gzip"), valid, 1);
        Path last = damagedGzip.resources.resolve("part-002.gz");
        byte[] compressed = Files.readAllBytes(last); compressed[compressed.length - 1] ^= 1; Files.write(last, compressed);
        Properties properties = properties(damagedGzip.resources.resolve("manifest.properties"));
        properties.setProperty("part.2.sha256", hash(compressed)); save(properties, damagedGzip.resources.resolve("manifest.properties"));
        expectIo(() -> DictionaryRepository.loadBundled(damagedGzip.cache, damagedGzip.source), "gzip trailer is checked even when part hash matches");
        assertNoPublished(damagedGzip.cache);
    }

    private static void largeIndex(Path root) throws Exception {
        Files.createDirectories(root);
        int count = 2_000_003, recordSize = 44, tableEnd = 8 + (count + 1) * 4;
        Path raw = root.resolve("large.wnd2"), resources = Files.createDirectories(root.resolve("resources"));
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(raw), 1024 * 1024))) {
            output.writeInt(MAGIC); output.writeInt(count);
            for (int i = 0; i <= count; i++) output.writeInt(tableEnd + i * recordSize);
            for (int i = 0; i < count; i++) {
                byte[] key = numbered(i).getBytes(StandardCharsets.US_ASCII);
                output.writeInt(key.length); output.write(key); output.writeInt(key.length); output.write(key);
                for (int field = 0; field < 5; field++) output.writeInt(0);
            }
        }
        Path compressed = root.resolve("large.gz");
        try (OutputStream output = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(compressed))); InputStream input = Files.newInputStream(raw)) { input.transferTo(output); }
        Properties manifest = baseManifest(count, Files.size(raw), hash(raw));
        int parts = 0;
        try (InputStream input = Files.newInputStream(compressed)) {
            for (;;) {
                byte[] bytes = input.readNBytes(4 * 1024 * 1024); if (bytes.length == 0) break;
                String name = String.format(Locale.ROOT, "part-%03d.gz", parts);
                Files.write(resources.resolve(name), bytes); manifest.setProperty("part." + parts, name);
                manifest.setProperty("part." + parts + ".sha256", hash(bytes)); parts++;
            }
        }
        manifest.setProperty("parts", Integer.toString(parts)); save(manifest, resources.resolve("manifest.properties"));
        System.gc(); long before = usedHeap(); long started = System.nanoTime();
        DictionaryRepository repository = DictionaryRepository.loadBundled(root.resolve("cache"), new ResourceSource(resources));
        System.gc(); long retained = Math.max(0, usedHeap() - before);
        check(repository.size() == count, "more than two million mapped records are supported");
        check(repository.lookup(numbered(0)).isPresent() && repository.lookup(numbered(1_234_567)).isPresent()
                && repository.lookup(numbered(count - 1)).isPresent(), "first, middle and last large-index offsets resolve correctly");
        check(repository.lookup(numbered(count)).isEmpty(), "large index respects the final boundary");
        check(repository.suggest("w200000", 10).equals(List.of("w2000000", "w2000001", "w2000002")), "large prefix search stops at its actual final record");
        check(retained < 16L * 1024 * 1024, "mapped large index retains less than 16 MiB of Java heap");
        System.out.printf(Locale.ROOT, "Synthetic WND2: %,d entries; %.1f MiB raw; %.3f s first load; %.2f MiB retained heap; -Xmx96m%n",
                count, Files.size(raw) / 1048576.0, (System.nanoTime() - started) / 1_000_000_000.0, retained / 1048576.0);
    }

    private static String numbered(int value) { return "w" + Integer.toString(10_000_000 + value).substring(1); }
    private static long usedHeap() { return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(); }
    private static String[] row(String key, String word, String meaning) { return new String[]{key, word, "音标", meaning, "definition", "tag", "s:forms"}; }
    private static byte[] raw(List<String[]> rows) throws Exception {
        List<byte[]> records = new ArrayList<>();
        for (String[] row : rows) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) { for (String field : row) { byte[] utf = field.getBytes(StandardCharsets.UTF_8); output.writeInt(utf.length); output.write(utf); } }
            records.add(bytes.toByteArray());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC); output.writeInt(rows.size()); int offset = 8 + (rows.size() + 1) * 4;
            output.writeInt(offset); for (byte[] record : records) { offset += record.length; output.writeInt(offset); }
            for (byte[] record : records) output.write(record);
        }
        return bytes.toByteArray();
    }
    private static Fixture fixture(Path root, byte[] raw, int count) throws Exception {
        Path resources = Files.createDirectories(root.resolve("resources")), cache = Files.createDirectories(root.resolve("cache"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(raw); }
        byte[] compressed = bytes.toByteArray(); int[] ends = {1, 7, compressed.length}; int start = 0;
        Properties manifest = baseManifest(count, raw.length, hash(raw)); manifest.setProperty("parts", "3");
        for (int i = 0; i < ends.length; i++) {
            String name = String.format(Locale.ROOT, "part-%03d.gz", i); byte[] part = Arrays.copyOfRange(compressed, start, ends[i]); start = ends[i];
            Files.write(resources.resolve(name), part); manifest.setProperty("part." + i, name); manifest.setProperty("part." + i + ".sha256", hash(part));
        }
        save(manifest, resources.resolve("manifest.properties")); return new Fixture(resources, cache, raw, new ResourceSource(resources));
    }
    private static Properties baseManifest(int count, long bytes, String hash) {
        Properties result = new Properties(); result.setProperty("format", "WND2"); result.setProperty("entries", Integer.toString(count));
        result.setProperty("raw.bytes", Long.toString(bytes)); result.setProperty("raw.sha256", hash); return result;
    }
    private static Properties properties(Path path) throws Exception { Properties properties = new Properties(); try (InputStream in = Files.newInputStream(path)) { properties.load(in); } return properties; }
    private static void save(Properties values, Path path) throws Exception { try (OutputStream output = Files.newOutputStream(path)) { values.store(output, "test fixture"); } }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) { byte[] bytes = new byte[65536]; int count; while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void assertNoPublished(Path cache) throws Exception {
        try (var files = Files.list(cache)) { check(files.findAny().isEmpty(), "failure publishes no partial or invalid cache and cleans temporary data"); }
    }
    private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
    private static void expectIo(Action action, String label) throws Exception { try { action.run(); throw new AssertionError(label); } catch (IOException expected) { assertions++; } }
    private static void expectUnsupported(Action action, String label) throws Exception { try { action.run(); throw new AssertionError(label); } catch (UnsupportedOperationException expected) { assertions++; } }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private record Fixture(Path resources, Path cache, byte[] raw, ResourceSource source) { }
    private static final class ResourceSource implements DictionaryResources.Source {
        final Path directory; final List<String> opened = new ArrayList<>();
        ResourceSource(Path directory) { this.directory = directory; }
        @Override public InputStream open(String resource) throws IOException {
            opened.add(resource);
            if (!resource.startsWith("/dictionary/")) return null;
            Path file = directory.resolve(resource.substring("/dictionary/".length()));
            return Files.exists(file) ? Files.newInputStream(file) : null;
        }
    }
}
