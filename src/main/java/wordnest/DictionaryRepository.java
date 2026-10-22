package wordnest;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Immutable offline dictionary. Only normalized word keys and binary offsets are
 * indexed; definitions are decoded on demand instead of retaining millions of strings.
 */
public final class DictionaryRepository {
    private static final int MAGIC = 0x574e4431; // ASCII WND1
    private static final int MAX_RECORDS = 2_000_000;
    private static final int MAX_FIELD_BYTES = 1_048_576;
    private static final int MAX_TOTAL_BYTES = 512 * 1024 * 1024;
    private final byte[] data;
    private final String[] keys;
    private final int[] offsets;

    private DictionaryRepository(byte[] data, String[] keys, int[] offsets) {
        this.data = data;
        this.keys = keys;
        this.offsets = offsets;
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
        int index = Arrays.binarySearch(keys, normalize(word));
        return index < 0 ? Optional.empty() : Optional.of(decodeEntry(index));
    }

    /** Returns at most limit original spellings in alphabetical prefix order. */
    public List<String> suggest(String prefix, int limit) {
        String key = normalize(prefix);
        if (key.isEmpty() || limit <= 0) {
            return List.of();
        }
        int first = Arrays.binarySearch(keys, key);
        first = first < 0 ? -first - 1 : first;
        List<String> suggestions = new ArrayList<>(Math.min(limit, 32));
        for (int index = first; index < keys.length && suggestions.size() < limit; index++) {
            if (!keys[index].startsWith(key)) {
                break;
            }
            ByteBuffer buffer = ByteBuffer.wrap(data);
            buffer.position(offsets[index]);
            suggestions.add(readString(buffer));
        }
        return List.copyOf(suggestions);
    }

    public int size() {
        return keys.length;
    }

    private WordEntry decodeEntry(int index) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.position(offsets[index]);
        return new WordEntry(readString(buffer), readString(buffer), readString(buffer),
                readString(buffer), readString(buffer), readString(buffer));
    }

    private String readString(ByteBuffer buffer) {
        int length = buffer.getInt();
        String value = new String(data, buffer.position(), length, StandardCharsets.UTF_8);
        buffer.position(buffer.position() + length);
        return value;
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
