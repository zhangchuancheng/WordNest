package wordnest;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Verified, content-addressed extraction of split bundled WND2 resources. */
final class DictionaryResources {
    @FunctionalInterface interface Source { InputStream open(String resource) throws IOException; }
    record Cached(Path file, int entries, long bytes) { }
    private record Part(String name, String sha256) { }
    private record Manifest(int entries, long bytes, String sha256, List<Part> parts) { }
    private static final int BUFFER_SIZE = 64 * 1024;

    static Cached prepare(Path cacheDirectory, Source source) throws IOException {
        Objects.requireNonNull(cacheDirectory, "cacheDirectory"); Objects.requireNonNull(source, "source");
        Manifest manifest = manifest(source);
        Path directory = cacheDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);
        String stem = "dictionary-" + manifest.sha256();
        Path primary = directory.resolve(stem + ".wnd2");
        if (matches(primary, manifest)) return new Cached(primary, manifest.entries(), manifest.bytes());
        // A previous process may still map the primary filename on Windows. A verified alternate
        // avoids force-unmapping or overwriting files that a live process is using.
        try (var alternatives = Files.newDirectoryStream(directory, stem + "-*.wnd2")) {
            for (Path alternative : alternatives)
                if (matches(alternative, manifest)) return new Cached(alternative, manifest.entries(), manifest.bytes());
        }
        Path temporary = Files.createTempFile(directory, ".dictionary-", ".tmp");
        boolean published = false;
        try {
            extract(temporary, manifest, source);
            DictionaryRepository.validateIndexedFile(temporary, manifest.entries());
            Path destination = primary;
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                throw new IOException("词库缓存位置不支持安全替换，请使用本机磁盘目录", ex);
            } catch (IOException lockedOrUnavailable) {
                destination = directory.resolve(stem + "-" + UUID.randomUUID() + ".wnd2");
                try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException fallbackFailure) {
                    fallbackFailure.addSuppressed(lockedOrUnavailable);
                    throw new IOException("无法写入词库缓存；请检查目录权限，或退出使用此缓存的旧版程序后重试", fallbackFailure);
                }
            }
            published = true;
            return new Cached(destination, manifest.entries(), manifest.bytes());
        } catch (IOException | RuntimeException failure) {
            if (!published) try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static Manifest manifest(Source source) throws IOException {
        Properties values = new Properties();
        try (InputStream input = required(source, "/dictionary/manifest.properties")) {
            byte[] bytes = input.readNBytes(64 * 1024 + 1);
            if (bytes.length > 64 * 1024) throw new IOException("词库清单过大");
            values.load(new java.io.ByteArrayInputStream(bytes));
        } catch (IllegalArgumentException ex) { throw new IOException("词库清单格式不正确", ex); }
        try {
            if (!"WND2".equals(values.getProperty("format"))) throw new IllegalArgumentException("Unknown dictionary format");
            int entries = Integer.parseInt(require(values, "entries"));
            long bytes = Long.parseLong(require(values, "raw.bytes"));
            int count = Integer.parseInt(require(values, "parts"));
            if (entries < 0 || entries > DictionaryRepository.MAX_INDEXED_RECORDS || bytes > Integer.MAX_VALUE
                    || bytes < 8L + ((long) entries + 1) * Integer.BYTES || count <= 0 || count > 128)
                throw new IllegalArgumentException("Invalid dictionary limits");
            String rawHash = hashValue(require(values, "raw.sha256"));
            List<Part> parts = new ArrayList<>(); HashSet<String> names = new HashSet<>();
            for (int i = 0; i < count; i++) {
                String name = require(values, "part." + i);
                if (!name.matches("part-[0-9]{3,5}\\.gz") || !names.add(name)) throw new IllegalArgumentException("Invalid resource part name");
                parts.add(new Part(name, hashValue(require(values, "part." + i + ".sha256"))));
            }
            return new Manifest(entries, bytes, rawHash, List.copyOf(parts));
        } catch (IllegalArgumentException ex) { throw new IOException("词库清单字段无效或缺失", ex); }
    }

    private static String require(Properties values, String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value.strip();
    }

    private static String hashValue(String value) {
        if (!value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean matches(Path file, Manifest manifest) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != manifest.bytes()) return false;
            MessageDigest digest = sha256();
            try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[BUFFER_SIZE]; int count; long bytes = 0;
                while ((count = input.read(buffer)) != -1) { bytes += count; if (bytes > manifest.bytes()) return false; digest.update(buffer, 0, count); }
                return bytes == manifest.bytes() && hex(digest).equals(manifest.sha256());
            }
        } catch (IOException ex) { return false; }
    }

    private static void extract(Path target, Manifest manifest, Source source) throws IOException {
        List<InputStream> parts = new ArrayList<>();
        try {
            for (Part part : manifest.parts()) parts.add(new VerifiedPart(required(source, "/dictionary/" + part.name()), part));
            try (InputStream sequence = new SequenceInputStream(Collections.enumeration(parts));
                 InputStream gzip = new GZIPInputStream(sequence, BUFFER_SIZE);
                 FileChannel output = FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                MessageDigest digest = sha256(); byte[] bytes = new byte[BUFFER_SIZE]; long written = 0; int count;
                while ((count = gzip.read(bytes)) != -1) {
                    written += count;
                    if (written > manifest.bytes()) throw new IOException("解压词库超过清单声明的大小");
                    digest.update(bytes, 0, count);
                    ByteBuffer chunk = ByteBuffer.wrap(bytes, 0, count);
                    while (chunk.hasRemaining()) output.write(chunk);
                }
                if (written != manifest.bytes() || !hex(digest).equals(manifest.sha256()))
                    throw new IOException("词库大小或 SHA-256 校验失败，请重新获取完整程序包");
                output.force(true);
            }
        } catch (IOException | RuntimeException failure) {
            for (InputStream part : parts) try { part.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private static InputStream required(Source source, String name) throws IOException {
        InputStream input = source.open(name);
        if (input == null) throw new IOException("找不到随软件附带的词库资源：" + name);
        return input;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(MessageDigest digest) { return HexFormat.of().formatHex(digest.digest()); }

    /** Closing also verifies unread trailing compressed bytes, including parts buffered past gzip EOF. */
    private static final class VerifiedPart extends FilterInputStream {
        private final MessageDigest digest = sha256();
        private final Part part;
        private boolean verified, failed, closed;
        VerifiedPart(InputStream input, Part part) { super(new BufferedInputStream(input)); this.part = part; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; int count = read(one, 0, 1); return count == -1 ? -1 : one[0] & 0xff;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count == -1) verify(); else if (count > 0) digest.update(bytes, offset, count);
            return count;
        }
        private void verify() throws IOException {
            if (failed) throw new IOException("词库分片校验失败：" + part.name());
            if (verified) return;
            if (!hex(digest).equals(part.sha256())) {
                failed = true;
                throw new IOException("词库分片校验失败：" + part.name());
            }
            verified = true;
        }
        @Override public void close() throws IOException {
            // GZIPInputStream may probe another member and treat an IOException as EOF.
            // A failed part verification must therefore remain a failure on every close.
            if (closed) { if (failed) verify(); return; }
            try { byte[] buffer = new byte[BUFFER_SIZE]; while (read(buffer) != -1) { } }
            finally { closed = true; in.close(); }
        }
    }
}
