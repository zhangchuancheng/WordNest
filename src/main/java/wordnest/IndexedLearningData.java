package wordnest;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Compact immutable index: record bodies stay UTF-8 until a word is requested. */
final class IndexedLearningData {
    static final int MAX_RESOURCE_BYTES = 192 * 1024 * 1024;
    static final int MAX_FIELD_BYTES = 1024 * 1024;
    static final int MAX_RECORDS = 2_000_000;
    private static final Set<String> KINDS = Set.of("example", "collocation", "derived",
            "synonym", "etymology", "morphology", "source", "root_related");
    private final byte[] data;
    private final String[] keys;
    private final int[] offsets;
    private final int fieldCount;
    private final boolean tsv;
    private final int words;

    private IndexedLearningData(byte[] data, String[] keys, int[] offsets, int fields, boolean tsv) {
        this.data = data;
        this.keys = keys;
        this.offsets = offsets;
        this.fieldCount = fields;
        this.tsv = tsv;
        boolean sorted = true;
        for (int i = 1; i < keys.length; i++) {
            if (compare(keys[i - 1], offsets[i - 1], keys[i], offsets[i]) > 0) {
                sorted = false;
                break;
            }
        }
        if (!sorted) sort(keys, offsets, 0, keys.length - 1);
        int unique = 0;
        for (int i = 0; i < keys.length; i++) {
            if (i == 0 || !keys[i].equals(keys[i - 1])) unique++;
            else keys[i] = keys[i - 1]; // Share repeated word keys across senses/relations.
        }
        words = unique;
    }

    static IndexedLearningData empty(int fields) {
        return new IndexedLearningData(new byte[0], new String[0], new int[0], fields, false);
    }

    static IndexedLearningData loadGzip(InputStream source, int magic, int fields) throws IOException {
        return loadGzip(source, magic, fields, MAX_RESOURCE_BYTES);
    }

    /** The lower limit overload exercises the exact resource guard without huge test allocations. */
    static IndexedLearningData loadGzip(InputStream source, int magic, int fields, int maximumBytes)
            throws IOException {
        if (fields < 1 || fields > 16) throw new IllegalArgumentException("Invalid field count");
        byte[] bytes = readGzip(source, maximumBytes);
        if (bytes.length < 8) throw new IOException("学习资料头部不完整。");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != magic) throw new IOException("学习资料格式标识不正确。");
        int count = buffer.getInt();
        if (count < 0 || count > MAX_RECORDS || count > (bytes.length - 8) / (fields * 4 + 1)) {
            throw new IOException("学习资料记录数量不合法：" + count);
        }
        String[] keys = new String[count];
        int[] offsets = new int[count];
        Utf8Validator validator = new Utf8Validator();
        for (int record = 0; record < count; record++) {
            offsets[record] = buffer.position();
            for (int field = 0; field < fields; field++) {
                if (buffer.remaining() < 4) throw new IOException("学习资料字段长度不完整。");
                int length = buffer.getInt();
                checkField(length);
                if (length > buffer.remaining()) throw new IOException("学习资料字段内容被截断。");
                int position = buffer.position();
                validator.validate(bytes, position, length);
                if (field == 0) {
                    keys[record] = normalize(new String(bytes, position, length, StandardCharsets.UTF_8));
                    if (keys[record].isEmpty()) throw new IOException("学习资料包含空单词。");
                }
                buffer.position(position + length);
            }
        }
        if (buffer.hasRemaining()) throw new IOException("学习资料包含尾随数据。");
        return new IndexedLearningData(bytes, keys, offsets, fields, false);
    }

    static IndexedLearningData loadTsv(InputStream source) throws IOException {
        return loadTsv(source, MAX_RESOURCE_BYTES);
    }

    static IndexedLearningData loadTsv(InputStream source, int maximumBytes) throws IOException {
        byte[] bytes;
        try (InputStream input = Objects.requireNonNull(source, "source")) {
            bytes = readBounded(input, maximumBytes, null);
        }
        new Utf8Validator().validate(bytes, 0, bytes.length);
        int start = hasBom(bytes) ? 3 : 0;
        int records = 0;
        for (int position = start; position < bytes.length;) {
            int end = lineEnd(bytes, position);
            if (!ignoredLine(bytes, position, end)) {
                if (++records > MAX_RECORDS) throw new IOException("精选资料记录数量过多。");
            }
            position = end < bytes.length ? end + 1 : end;
        }
        String[] keys = new String[records];
        int[] offsets = new int[records];
        int record = 0;
        for (int position = start; position < bytes.length;) {
            int end = lineEnd(bytes, position);
            if (!ignoredLine(bytes, position, end)) {
                String[] row = decodeTsv(bytes, position, end, true);
                if (!KINDS.contains(row[0])) throw new IOException("未知的精选资料类别：" + row[0]);
                String key = normalize(row[1]);
                if (key.isEmpty()) throw new IOException("精选资料包含空单词。");
                keys[record] = key;
                offsets[record++] = position;
            }
            position = end < bytes.length ? end + 1 : end;
        }
        return new IndexedLearningData(bytes, keys, offsets, 5, true);
    }

    int words() { return words; }

    List<String[]> rows(String word) {
        String key = normalize(word);
        if (key.isEmpty()) return List.of();
        int low = 0;
        int high = keys.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (keys[middle].compareTo(key) < 0) low = middle + 1;
            else high = middle;
        }
        if (low == keys.length || !keys[low].equals(key)) return List.of();
        List<String[]> result = new ArrayList<>();
        for (int index = low; index < keys.length && keys[index].equals(key); index++) {
            result.add(decode(index));
        }
        return List.copyOf(result);
    }

    private String[] decode(int index) {
        if (tsv) {
            try {
                return decodeTsv(data, offsets[index], lineEnd(data, offsets[index]), false);
            } catch (IOException impossible) {
                throw new IllegalStateException("Previously validated TSV became invalid", impossible);
            }
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.position(offsets[index]);
        String[] fields = new String[fieldCount];
        for (int field = 0; field < fieldCount; field++) {
            int length = buffer.getInt();
            fields[field] = new String(data, buffer.position(), length, StandardCharsets.UTF_8);
            buffer.position(buffer.position() + length);
        }
        return fields;
    }

    private static String[] decodeTsv(byte[] bytes, int start, int end, boolean validate) throws IOException {
        if (end > start && bytes[end - 1] == '\r') end--;
        String[] fields = new String[5];
        int count = 0;
        int beginning = start;
        for (int position = start; position <= end; position++) {
            if (position == end || bytes[position] == '\t') {
                if (count == fields.length) throw new IOException("精选资料必须恰好包含五列。");
                int length = position - beginning;
                if (validate) checkField(length);
                fields[count++] = new String(bytes, beginning, length, StandardCharsets.UTF_8);
                beginning = position + 1;
            }
        }
        if (count != fields.length) throw new IOException("精选资料必须恰好包含五列。");
        return fields;
    }

    private static boolean ignoredLine(byte[] data, int start, int end) {
        int actualEnd = end > start && data[end - 1] == '\r' ? end - 1 : end;
        if (actualEnd == start || data[start] == '#') return true;
        byte[] header = "kind\tword\tfirst\tsecond\tthird".getBytes(StandardCharsets.US_ASCII);
        return actualEnd - start == header.length && Arrays.equals(data, start, actualEnd, header, 0, header.length);
    }

    private static int lineEnd(byte[] data, int start) {
        int end = start;
        while (end < data.length && data[end] != '\n') end++;
        return end;
    }

    private static boolean hasBom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf;
    }

    private static void checkField(int length) throws IOException {
        if (length < 0 || length > MAX_FIELD_BYTES) throw new IOException("学习资料字段长度不合法：" + length);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Utf8Validator {
        private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        private final CharBuffer characters = CharBuffer.allocate(2048);

        void validate(byte[] data, int position, int length) throws IOException {
            decoder.reset();
            ByteBuffer input = ByteBuffer.wrap(data, position, length);
            while (true) {
                characters.clear();
                var result = decoder.decode(input, characters, true);
                if (result.isError()) result.throwException();
                if (result.isUnderflow()) break;
            }
            characters.clear();
            var result = decoder.flush(characters);
            if (result.isError()) result.throwException();
        }
    }

    /** Read one strict gzip member, validating its CRC, length and physical EOF. */
    private static byte[] readGzip(InputStream source, int maximumBytes) throws IOException {
        try (PushbackInputStream input = new PushbackInputStream(
                new BufferedInputStream(Objects.requireNonNull(source, "source")), 8192)) {
            CRC32 headerCrc = new CRC32();
            if (headerByte(input, headerCrc) != 0x1f || headerByte(input, headerCrc) != 0x8b
                    || headerByte(input, headerCrc) != 8) throw new IOException("学习资料不是有效的 gzip 文件。");
            int flags = headerByte(input, headerCrc);
            if ((flags & 0xe0) != 0) throw new IOException("gzip 包含保留标记。");
            for (int i = 0; i < 6; i++) headerByte(input, headerCrc);
            if ((flags & 4) != 0) {
                int length = headerByte(input, headerCrc) | headerByte(input, headerCrc) << 8;
                for (int i = 0; i < length; i++) headerByte(input, headerCrc);
            }
            if ((flags & 8) != 0) skipHeaderText(input, headerCrc);
            if ((flags & 16) != 0) skipHeaderText(input, headerCrc);
            if ((flags & 2) != 0) {
                int expected = required(input) | required(input) << 8;
                if (expected != ((int) headerCrc.getValue() & 0xffff)) throw new IOException("gzip 头部校验失败。");
            }
            Inflater inflater = new Inflater(true);
            try {
                Payload payload = new Payload(input, inflater);
                CRC32 crc = new CRC32();
                byte[] bytes = readBounded(payload, maximumBytes, crc);
                if (!inflater.finished()) throw new IOException("gzip 压缩数据不完整。");
                payload.restore(input);
                long expectedCrc = littleEndianInt(input);
                long expectedSize = littleEndianInt(input);
                if (crc.getValue() != expectedCrc || bytes.length != expectedSize) {
                    throw new IOException("gzip 内容校验失败。");
                }
                if (input.read() != -1) throw new IOException("gzip 文件包含尾随数据或额外成员。");
                return bytes;
            } finally {
                inflater.end();
            }
        }
    }

    private static final class Payload extends InflaterInputStream {
        Payload(InputStream input, Inflater inflater) { super(input, inflater, 8192); }
        void restore(PushbackInputStream input) throws IOException {
            int remaining = inf.getRemaining();
            if (remaining > 0) input.unread(buf, len - remaining, remaining);
        }
    }

    private static byte[] readBounded(InputStream input, int maximumBytes, CRC32 crc) throws IOException {
        if (maximumBytes < 0 || maximumBytes > MAX_RESOURCE_BYTES) throw new IllegalArgumentException("Invalid size limit");
        List<byte[]> chunks = new ArrayList<>();
        int total = 0;
        while (true) {
            int capacity = Math.min(64 * 1024, maximumBytes - total + 1);
            byte[] chunk = new byte[capacity];
            int used = 0;
            while (used < capacity) {
                int count = input.read(chunk, used, capacity - used);
                if (count == -1) break;
                if (count == 0) {
                    int value = input.read();
                    if (value == -1) break;
                    chunk[used++] = (byte) value;
                } else used += count;
            }
            if (used > maximumBytes - total) throw new IOException("学习资料解压内容超过资源大小上限。");
            if (used == 0) break;
            if (crc != null) crc.update(chunk, 0, used);
            total += used;
            chunks.add(used == chunk.length ? chunk : Arrays.copyOf(chunk, used));
            if (used < capacity) break;
        }
        byte[] result = new byte[total];
        int position = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, position, chunk.length);
            position += chunk.length;
        }
        return result;
    }

    private static int required(InputStream input) throws IOException {
        int value = input.read();
        if (value == -1) throw new EOFException("学习资料 gzip 文件被截断。");
        return value;
    }

    private static int headerByte(InputStream input, CRC32 crc) throws IOException {
        int value = required(input);
        crc.update(value);
        return value;
    }

    private static void skipHeaderText(InputStream input, CRC32 crc) throws IOException {
        for (int length = 0; length <= MAX_FIELD_BYTES; length++) {
            if (headerByte(input, crc) == 0) return;
        }
        throw new IOException("gzip 头部文本过长。");
    }

    private static long littleEndianInt(InputStream input) throws IOException {
        return (long) required(input) | (long) required(input) << 8
                | (long) required(input) << 16 | (long) required(input) << 24;
    }

    private static int compare(String leftKey, int leftOffset, String rightKey, int rightOffset) {
        int comparison = leftKey.compareTo(rightKey);
        return comparison != 0 ? comparison : Integer.compare(leftOffset, rightOffset);
    }

    private static void sort(String[] keys, int[] offsets, int left, int right) {
        while (left < right) {
            int pivotIndex = left + (right - left) / 2;
            String pivotKey = keys[pivotIndex];
            int pivotOffset = offsets[pivotIndex];
            int low = left;
            int high = right;
            while (low <= high) {
                while (compare(keys[low], offsets[low], pivotKey, pivotOffset) < 0) low++;
                while (compare(keys[high], offsets[high], pivotKey, pivotOffset) > 0) high--;
                if (low <= high) {
                    String key = keys[low]; keys[low] = keys[high]; keys[high] = key;
                    int offset = offsets[low]; offsets[low] = offsets[high]; offsets[high] = offset;
                    low++; high--;
                }
            }
            if (high - left < right - low) {
                if (left < high) sort(keys, offsets, left, high);
                left = low;
            } else {
                if (low < right) sort(keys, offsets, low, right);
                right = high;
            }
        }
    }
}
