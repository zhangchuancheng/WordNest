package wordnest;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Immutable offline dictionary. WND1 remains supported for older streams; bundled WND2
 * retains only a read-only mapping, decoding a handful of keys during each binary search.
 */
public final class DictionaryRepository {
    private static final int MAGIC = 0x574e4431; // ASCII WND1
    private static final int MAX_RECORDS = 2_000_000;
    private static final int MAX_FIELD_BYTES = 1_048_576;
    private static final int MAX_TOTAL_BYTES = 512 * 1024 * 1024;
    static final int INDEXED_MAGIC = 0x574e4432;
    static final int MAX_INDEXED_RECORDS = 8_000_000;
    private final byte[] data;
    private final String[] keys;
    private final int[] offsets;
    private final ByteBuffer mapped;
    private final int indexedCount;

    private DictionaryRepository(byte[] data, String[] keys, int[] offsets) {
        this.data = data;
        this.keys = keys;
        this.offsets = offsets;
        this.mapped = null;
        this.indexedCount = 0;
    }

    private DictionaryRepository(ByteBuffer mapped, int count) {
        this.data = null; this.keys = null; this.offsets = null;
        this.mapped = mapped.asReadOnlyBuffer(); this.indexedCount = count;
    }

    /**
     * Load verified bundled parts into a content-addressed cache, then map the index.
     * The mapping lives as long as the repository; Windows may lock its file until JVM exit.
     * No unsafe forced unmapping or in-place replacement of an active mapping is performed.
     */
    public static DictionaryRepository loadBundled(Path cacheDirectory) throws IOException {
        return loadBundled(cacheDirectory, name -> DictionaryRepository.class.getResourceAsStream(name));
    }

    static DictionaryRepository loadBundled(Path cacheDirectory, DictionaryResources.Source source) throws IOException {
        DictionaryResources.Cached result = DictionaryResources.prepare(cacheDirectory, source);
        try (FileChannel channel = FileChannel.open(result.file(), StandardOpenOption.READ)) {
            long size = channel.size();
            if (size != result.bytes() || size > Integer.MAX_VALUE) throw new IOException("缓存词库大小不正确");
            ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            verifyIndex(mapped, result.entries());
            return new DictionaryRepository(mapped, result.entries());
        }
    }

    /** Validate once before a newly extracted file is published; no mappings or retained key arrays. */
    static void validateIndexedFile(Path file, int expectedCount) throws IOException {
        long size = Files.size(file);
        if (size < 12 || size > Integer.MAX_VALUE) throw new IOException("WND2 词库大小超出索引范围");
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != INDEXED_MAGIC) throw new IOException("词库格式不正确：缺少 WND2 标识");
            int count = input.readInt();
            long tableEnd = 8L + ((long) count + 1) * Integer.BYTES;
            if (count != expectedCount || count < 0 || count > MAX_INDEXED_RECORDS || tableEnd > size)
                throw new IOException("WND2 词库记录数量不正确");
            // This temporary offset table is released before mapping; no per-word Strings are retained.
            int[] positions = new int[count + 1];
            for (int i = 0; i <= count; i++) {
                positions[i] = input.readInt();
                if (positions[i] < tableEnd || positions[i] > size
                        || (i == 0 && positions[i] != tableEnd)
                        || (i > 0 && (long) positions[i] - positions[i - 1] < 28))
                    throw new IOException("WND2 词库偏移无效");
            }
            if (positions[count] != size) throw new IOException("WND2 词库结束偏移不正确");
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            String previous = null;
            for (int i = 0; i < count; i++) {
                long position = positions[i];
                String canonical = null;
                for (int field = 0; field < 7; field++) {
                    if (position + Integer.BYTES > positions[i + 1]) throw new IOException("WND2 词条字段缺失");
                    int length = input.readInt(); position += Integer.BYTES;
                    if (length < 0 || length > MAX_FIELD_BYTES || position + length > positions[i + 1])
                        throw new IOException("WND2 词条字段长度无效");
                    byte[] bytes = input.readNBytes(length);
                    if (bytes.length != length) throw new EOFException("Incomplete WND2 field");
                    String value = decoder.decode(ByteBuffer.wrap(bytes)).toString(); position += length;
                    if (field == 0) {
                        canonical = value;
                        if (value.isEmpty() || !value.equals(normalize(value)) || (previous != null && previous.compareTo(value) >= 0))
                            throw new IOException("WND2 词头未规范排序或重复");
                    } else if (field == 1 && !normalize(value).equals(canonical))
                        throw new IOException("WND2 词头与显示单词不一致");
                }
                if (position != positions[i + 1]) throw new IOException("WND2 词条含多余数据");
                previous = canonical;
            }
            if (input.read() != -1) throw new IOException("WND2 词库含多余数据");
        } catch (EOFException | CharacterCodingException ex) {
            throw new IOException("WND2 词库不完整或包含无效 UTF-8", ex);
        }
    }

    /** The manifest SHA-256 authenticates cached content; repeat only cheap index boundary checks. */
    private static void verifyIndex(ByteBuffer map, int expectedCount) throws IOException {
        if (map.capacity() < 12 || map.getInt(0) != INDEXED_MAGIC || map.getInt(4) != expectedCount)
            throw new IOException("WND2 缓存标识或数量不正确");
        long tableEnd = 8L + ((long) expectedCount + 1) * Integer.BYTES;
        if (expectedCount < 0 || expectedCount > MAX_INDEXED_RECORDS || tableEnd > map.capacity())
            throw new IOException("WND2 缓存索引范围不正确");
        int previous = -1;
        for (int i = 0; i <= expectedCount; i++) {
            int offset = map.getInt(8 + i * Integer.BYTES);
            if (offset < tableEnd || offset > map.capacity() || (i == 0 && offset != tableEnd)
                    || (i > 0 && (long) offset - previous < 28)) throw new IOException("WND2 缓存偏移不正确");
            previous = offset;
        }
        if (previous != map.capacity()) throw new IOException("WND2 缓存结束位置不正确");
    }

    /** Loads and closes the supplied compressed stream. Run on a worker thread. */
    public static DictionaryRepository load(InputStream gzipInput) throws IOException {
        Objects.requireNonNull(gzipInput, "gzipInput");
        try (InputStream source = gzipInput;
             DataInputStream input = new DataInputStream(
                     new BufferedInputStream(new GZIPInputStream(source)))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("词库格式不正确：缺少 WND1 标识。");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_RECORDS) {
                throw new IOException("词库记录数量不合法：" + count);
            }
            String[] keys = new String[count];
            int[] offsets = new int[count];
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(count * 96, 64 * 1024 * 1024));
            DataOutputStream output = new DataOutputStream(bytes);
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            for (int i = 0; i < count; i++) {
                offsets[i] = bytes.size();
                for (int field = 0; field < 6; field++) {
                    int length = input.readInt();
                    if (length < 0 || length > MAX_FIELD_BYTES) {
                        throw new IOException("词库字段长度不合法：" + length);
                    }
                    if (bytes.size() > MAX_TOTAL_BYTES - length - Integer.BYTES) {
                        throw new IOException("词库解压内容超过 512 MiB，已停止加载。");
                    }
                    byte[] value = input.readNBytes(length);
                    if (value.length != length) {
                        throw new EOFException("Incomplete dictionary field");
                    }
                    try {
                        var text = decoder.decode(ByteBuffer.wrap(value));
                        if (field == 0) {
                            keys[i] = normalize(text.toString());
                            if (keys[i].isEmpty()) {
                                throw new IOException("词库第 " + (i + 1) + " 条记录没有单词。");
                            }
                        }
                    } catch (CharacterCodingException ex) {
                        throw new IOException("词库字段不是有效的 UTF-8 文本。", ex);
                    }
                    output.writeInt(length);
                    output.write(value);
                }
            }
            // Reading through EOF verifies the gzip trailer/checksum too.
            if (input.read() != -1) {
                throw new IOException("词库含有未识别的尾随数据。");
            }
            sort(keys, offsets, 0, count - 1);
            // Keep the first source entry for alternative capitalization duplicates.
            int unique = 0;
            for (int i = 0; i < count; i++) {
                if (unique == 0 || !keys[i].equals(keys[unique - 1])) {
                    keys[unique] = keys[i];
                    offsets[unique] = offsets[i];
                    unique++;
                } else if (offsets[i] < offsets[unique - 1]) {
                    offsets[unique - 1] = offsets[i];
                }
            }
            if (unique != count) {
                keys = Arrays.copyOf(keys, unique);
                offsets = Arrays.copyOf(offsets, unique);
            }
            return new DictionaryRepository(bytes.toByteArray(), keys, offsets);
        } catch (EOFException ex) {
            throw new IOException("词库不完整，请重新获取完整文件。", ex);
        }
    }

    public Optional<WordEntry> lookup(String word) {
        int index = mapped == null ? Arrays.binarySearch(keys, normalize(word)) : binarySearch(normalize(word));
        return index < 0 ? Optional.empty() : Optional.of(decodeEntry(index));
    }

    /** Returns at most limit original spellings in alphabetical prefix order. */
    public List<String> suggest(String prefix, int limit) {
        String key = normalize(prefix);
        if (key.isEmpty() || limit <= 0) {
            return List.of();
        }
        int first = mapped == null ? Arrays.binarySearch(keys, key) : binarySearch(key);
        first = first < 0 ? -first - 1 : first;
        List<String> suggestions = new ArrayList<>(Math.min(limit, 32));
        for (int index = first; index < size() && suggestions.size() < limit; index++) {
            if (!(mapped == null ? keys[index] : indexedKey(index)).startsWith(key)) {
                break;
            }
            ByteBuffer buffer = record(index);
            suggestions.add(readString(buffer));
        }
        return List.copyOf(suggestions);
    }

    public int size() {
        return mapped == null ? keys.length : indexedCount;
    }

    private int binarySearch(String key) {
        int low = 0, high = indexedCount - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int comparison = indexedKey(middle).compareTo(key);
            if (comparison < 0) low = middle + 1;
            else if (comparison > 0) high = middle - 1;
            else return middle;
        }
        return -low - 1;
    }

    private String indexedKey(int index) {
        ByteBuffer cursor = mapped.duplicate();
        cursor.position(mapped.getInt(8 + index * Integer.BYTES));
        return readString(cursor);
    }

    private ByteBuffer record(int index) {
        if (mapped == null) { ByteBuffer cursor = ByteBuffer.wrap(data); cursor.position(offsets[index]); return cursor; }
        ByteBuffer cursor = mapped.duplicate();
        cursor.position(mapped.getInt(8 + index * Integer.BYTES));
        cursor.limit(mapped.getInt(8 + (index + 1) * Integer.BYTES));
        int keyLength = cursor.getInt(); cursor.position(cursor.position() + keyLength);
        return cursor;
    }

    private WordEntry decodeEntry(int index) {
        ByteBuffer buffer = record(index);
        return new WordEntry(readString(buffer), readString(buffer), readString(buffer),
                readString(buffer), readString(buffer), readString(buffer));
    }

    private String readString(ByteBuffer buffer) {
        int length = buffer.getInt();
        if (mapped == null) {
            String value = new String(data, buffer.position(), length, StandardCharsets.UTF_8);
            buffer.position(buffer.position() + length); return value;
        }
        byte[] value = new byte[length]; buffer.get(value);
        return new String(value, StandardCharsets.UTF_8);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Parallel-array quicksort; recurse into the smaller partition to bound stack use. */
    private static void sort(String[] keys, int[] offsets, int left, int right) {
        while (left < right) {
            String pivot = keys[left + (right - left) / 2];
            int low = left;
            int high = right;
            while (low <= high) {
                while (keys[low].compareTo(pivot) < 0) low++;
                while (keys[high].compareTo(pivot) > 0) high--;
                if (low <= high) {
                    String key = keys[low];
                    keys[low] = keys[high];
                    keys[high] = key;
                    int offset = offsets[low];
                    offsets[low] = offsets[high];
                    offsets[high] = offset;
                    low++;
                    high--;
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
