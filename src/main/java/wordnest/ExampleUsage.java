package wordnest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Manually checked word usage in a particular bilingual sentence, never inferred from a general entry. */
final class ExampleUsage {
    record Note(String partOfSpeech, String meaningInContext) { }

    private static final String HEADER = "word\tenglish\tpart_of_speech\tmeaning_in_context";
    private static final Map<String, Note> NOTES = load();

    private ExampleUsage() { }

    static Optional<Note> find(String word, String english) {
        return Optional.ofNullable(NOTES.get(key(word, english)));
    }

    static int count() { return NOTES.size(); }

    private static String key(String word, String english) {
        // Keep punctuation: changing it can change the meaning even when the words are identical.
        String sentence = Normalizer.normalize(english.strip(), Normalizer.Form.NFKC).replaceAll("\\s+", " ");
        return word.strip().toLowerCase(Locale.ROOT) + "\t" + sentence;
    }

    private static Map<String, Note> load() {
        try (InputStream input = ExampleUsage.class.getResourceAsStream("/example-usage.tsv")) {
            if (input == null) throw new IllegalStateException("缺少例句用法标注");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                if (!HEADER.equals(reader.readLine())) throw new IllegalStateException("例句用法标注表头不正确");
                Map<String, Note> notes = new HashMap<>();
                String line;
                int number = 1;
                while ((line = reader.readLine()) != null) {
                    number++;
                    String[] fields = line.split("\t", -1);
                    if (fields.length != 4 || fields[0].isBlank() || fields[1].isBlank()
                            || fields[2].isBlank() || fields[3].isBlank())
                        throw new IllegalStateException("例句用法标注第 " + number + " 行格式不正确");
                    String key = key(fields[0], fields[1]);
                    if (notes.putIfAbsent(key, new Note(fields[2], fields[3])) != null)
                        throw new IllegalStateException("例句用法标注重复：" + fields[0]);
                }
                return Map.copyOf(notes);
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法读取例句用法标注", error);
        }
    }
}
