package wordnest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;

/** Immutable offline learning material, indexed by spelling and decoded on demand. */
public final class LearningRepository {
    private static final int LEXICAL_MAGIC = 0x574e4c31; // WNL1
    private static final int ETYMOLOGY_MAGIC = 0x574e4531; // WNE1
    private static final String WORDNET_SOURCE = "Open English WordNet / Princeton WordNet";
    private static final Map<String, String> CURATED_SPELLING_ALIASES = Map.of(
            "organize", "organise", "recognize", "recognise",
            "behavior", "behaviour", "labor", "labour");
    private final IndexedLearningData lexical;
    private final IndexedLearningData etymology;
    private final IndexedLearningData curated;
    private final IndexedLearningData supplemental;

    private LearningRepository(IndexedLearningData lexical, IndexedLearningData etymology,
                               IndexedLearningData curated) {
        this(lexical, etymology, curated, IndexedLearningData.empty(6));
    }

    private LearningRepository(IndexedLearningData lexical, IndexedLearningData etymology,
                               IndexedLearningData curated, IndexedLearningData supplemental) {
        this.lexical = lexical;
        this.etymology = etymology;
        this.curated = curated;
        this.supplemental = supplemental;
    }

    /** Load on a worker thread. Required bundled resources are validated before use. */
    public static LearningRepository load() throws IOException {
        try (InputStream lexical = resource("/lexical.bin.gz");
             InputStream etymology = resource("/etymology.bin.gz");
             InputStream curated = resource("/learning-curated.tsv");
             InputStream supplemental = resource("/learning-open.bin.gz")) {
            LearningRepository base = load(lexical, etymology, curated);
            return new LearningRepository(base.lexical, base.etymology, base.curated,
                    IndexedLearningData.loadGzip(supplemental, 0x574e5331, 6));
        }
    }

    /** Owns and closes all supplied streams, including when an earlier resource fails. */
    static LearningRepository load(InputStream lexicalInput, InputStream etymologyInput,
                                   InputStream curatedInput) throws IOException {
        try (InputStream lexical = Objects.requireNonNull(lexicalInput, "lexicalInput");
             InputStream etymology = Objects.requireNonNull(etymologyInput, "etymologyInput");
             InputStream curated = Objects.requireNonNull(curatedInput, "curatedInput")) {
            return new LearningRepository(
                    IndexedLearningData.loadGzip(lexical, LEXICAL_MAGIC, 7),
                    IndexedLearningData.loadGzip(etymology, ETYMOLOGY_MAGIC, 5),
                    IndexedLearningData.loadTsv(curated));
        }
    }

    public static LearningRepository empty() {
        return new LearningRepository(IndexedLearningData.empty(7), IndexedLearningData.empty(5),
                IndexedLearningData.empty(5));
    }

    public List<LexicalSense> senses(String word) {
        List<LexicalSense> result = new ArrayList<>();
        for (String[] row : lexical.rows(word)) {
            List<Example> examples = new ArrayList<>();
            for (String value : values(row[4])) {
                int separator = value.indexOf('\t');
                String content = separator < 0 ? value : value.substring(0, separator);
                String source = separator < 0 ? WORDNET_SOURCE : value.substring(separator + 1);
                if (source.isBlank()) source = WORDNET_SOURCE;
                examples.add(new Example(content, source));
            }
            result.add(new LexicalSense(row[1], row[2], row[3], examples, values(row[5]), values(row[6])));
        }
        return List.copyOf(result);
    }

    public List<EtymologyLink> origins(String word) {
        List<EtymologyLink> result = new ArrayList<>();
        for (String[] row : etymology.rows(word)) {
            result.add(new EtymologyLink(row[1], row[2], row[3], row[4]));
        }
        return List.copyOf(result);
    }

    public List<CuratedItem> curated(String word) {
        List<CuratedItem> result = new ArrayList<>();
        List<String[]> rows = curated.rows(word);
        // Prefer an independently authored exact entry. Alias only the learning card.
        if (rows.isEmpty() && word != null) {
            String target = CURATED_SPELLING_ALIASES.get(word.trim().toLowerCase(java.util.Locale.ROOT));
            if (target != null) rows = curated.rows(target);
        }
        for (String[] row : rows) {
            result.add(new CuratedItem(row[0], row[2], row[3], row[4]));
        }
        return List.copyOf(result);
    }

    public int lexicalWords() { return lexical.words(); }
    public int originWords() { return etymology.words(); }
    public int curatedWords() { return curated.words(); }
    public int supplementalWords() { return supplemental.words(); }

    /** Public corpus records retain their own attribution, separate from editorial cards. */
    public List<SupplementItem> supplemental(String word) {
        List<SupplementItem> result = new ArrayList<>();
        for (String[] row : supplemental.rows(word)) {
            result.add(new SupplementItem(row[1], row[2], row[3], row[4], row[5]));
        }
        return List.copyOf(result);
    }

    private static InputStream resource(String name) throws IOException {
        InputStream input = LearningRepository.class.getResourceAsStream(name);
        if (input == null) throw new IOException("缺少离线学习资料：" + name);
        return input;
    }

    private static List<String> values(String value) {
        if (value.isEmpty()) return List.of();
        List<String> items = new ArrayList<>();
        for (String item : value.split("\n", -1)) {
            if (!item.isEmpty()) items.add(item);
        }
        return List.copyOf(items);
    }

    public record Example(String text, String source) { }

    public record LexicalSense(String id, String pos, String definition, List<Example> examples,
                               List<String> synonyms, List<String> derivations) {
        public LexicalSense {
            examples = List.copyOf(examples);
            synonyms = List.copyOf(synonyms);
            derivations = List.copyOf(derivations);
        }
    }

    public record EtymologyLink(String relation, String target, String language, String sourceUrl) { }
    public record CuratedItem(String kind, String first, String second, String third) { }
    public record SupplementItem(String kind, String first, String second, String attribution, String sourceUrl) { }
}
