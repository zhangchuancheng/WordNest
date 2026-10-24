package wordnest;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Exact lookup first; only bundled, source-recorded inflections may supply fallback candidates. */
final class LookupResolver {
    static final int MAX_CHARACTERS = 120;
    static final int MAX_WORDS = 8;
    static final int MAX_COMBINATIONS = 256;
    static final int MAX_CANDIDATES = 24;
    private static final Pattern WORD = Pattern.compile("[A-Za-z]+(?:['-][A-Za-z]+)*");
    private static final Pattern PHRASE = Pattern.compile("[A-Za-z]+(?:['-][A-Za-z]+)*(?: [A-Za-z]+(?:['-][A-Za-z]+)*)*");
    private static final String EDGE_PUNCTUATION = "\"'.,;:!?()[]{}<>-";
    enum MatchKind { EXACT, NORMALIZED, INFLECTION, AMBIGUOUS, NOT_FOUND, INVALID }
    record Result(String original, String normalized, MatchKind kind, List<WordEntry> candidates, boolean truncated) {
        Result { candidates = List.copyOf(candidates); }
        boolean hasSingleMatch() {
            return !truncated && candidates.size() == 1
                    && (kind == MatchKind.EXACT || kind == MatchKind.NORMALIZED || kind == MatchKind.INFLECTION);
        }
    }
    private final DictionaryRepository dictionary;
    private final Map<String, List<String>> inflections;

    LookupResolver(DictionaryRepository dictionary, Map<String, List<String>> inflections) {
        this.dictionary = Objects.requireNonNull(dictionary);
        Map<String, List<String>> copy = new HashMap<>();
        inflections.forEach((form, lemmas) -> copy.put(form.toLowerCase(Locale.ROOT), lemmas.stream().distinct().sorted().toList()));
        this.inflections = Map.copyOf(copy);
    }

    static LookupResolver withoutInflections(DictionaryRepository dictionary) { return new LookupResolver(dictionary, Map.of()); }

    static LookupResolver loadBundled(DictionaryRepository dictionary) throws IOException {
        InputStream source = LookupResolver.class.getResourceAsStream("/dictionary/inflections.tsv.gz");
        if (source == null) throw new IOException("缺少随包词形资料。");
        return load(dictionary, source);
    }

    /** UTF-8 gzip rows are form TAB lemma; duplicates and multiple lemmas are preserved as distinct alternatives. */
    static LookupResolver load(DictionaryRepository dictionary, InputStream source) throws IOException {
        Objects.requireNonNull(source);
        Map<String, List<String>> forms = new HashMap<>(); Map<String, String> lemmaPool = new HashMap<>();
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try (InputStream input = source; GZIPInputStream gzip = new GZIPInputStream(input);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, decoder))) {
            int rows = 0; long characters = 0;
            for (String line; (line = reader.readLine()) != null;) {
                if (++rows > 1_000_000 || (characters += line.length()) > 64_000_000 || line.length() > 256)
                    throw new IOException("词形资料超过支持的大小。");
                if (line.isBlank()) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 2 || !WORD.matcher(fields[0]).matches() || !WORD.matcher(fields[1]).matches())
                    throw new IOException("词形资料第 " + rows + " 行格式不正确。");
                String form = fields[0].toLowerCase(Locale.ROOT), lemma = fields[1];
                if (form.equalsIgnoreCase(lemma)) continue;
                lemma = lemmaPool.computeIfAbsent(lemma, value -> value);
                forms.computeIfAbsent(form, ignored -> new ArrayList<>()).add(lemma);
            }
        }
        return new LookupResolver(dictionary, forms);
    }

    Result resolve(String value) {
        if (value == null || value.length() > MAX_CHARACTERS || hasLineBreak(value))
            return result(value == null ? "" : value, "", MatchKind.INVALID, List.of(), false);
        String original = value.strip();
        String normalized = normalize(value);
        if (normalized.isEmpty())
            return result(original, normalized, MatchKind.INVALID, List.of(), false);
        // Preserve existing dictionary capitalization and literal punctuation before normalizing anything.
        Optional<WordEntry> exact = dictionary.lookup(original);
        if (exact.isPresent()) return result(original, normalized, MatchKind.EXACT, List.of(exact.get()), false);
        if (!normalized.equals(original)) {
            exact = dictionary.lookup(normalized);
            if (exact.isPresent()) return result(original, normalized, MatchKind.NORMALIZED, List.of(exact.get()), false);
        }
        if (!PHRASE.matcher(normalized).matches() || normalized.split(" ").length > MAX_WORDS)
            return result(original, normalized, MatchKind.NOT_FOUND, List.of(), false);
        List<String> combinations = List.of(""); boolean truncated = false;
        for (String token : normalized.split(" ")) {
            LinkedHashSet<String> options = new LinkedHashSet<>(); options.add(token);
            options.addAll(inflections.getOrDefault(token.toLowerCase(Locale.ROOT), List.of()));
            List<String> next = new ArrayList<>();
            outer: for (String prefix : combinations) for (String option : options) {
                if (next.size() >= MAX_COMBINATIONS) { truncated = true; break outer; }
                next.add(prefix.isEmpty() ? option : prefix + " " + option);
            }
            combinations = next;
        }
        Map<String, WordEntry> matches = new LinkedHashMap<>();
        for (String candidate : combinations) {
            if (candidate.equals(normalized)) continue;
            dictionary.lookup(candidate).ifPresent(entry -> matches.putIfAbsent(entry.word(), entry));
        }
        List<WordEntry> entries = matches.values().stream().sorted(Comparator.comparing(WordEntry::word, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(WordEntry::word)).toList();
        if (entries.size() > MAX_CANDIDATES) { entries = entries.subList(0, MAX_CANDIDATES); truncated = true; }
        MatchKind kind = entries.isEmpty() ? MatchKind.NOT_FOUND : entries.size() == 1 && !truncated ? MatchKind.INFLECTION : MatchKind.AMBIGUOUS;
        return result(original, normalized, kind, entries, truncated);
    }

    private static Result result(String original, String normalized, MatchKind kind, List<WordEntry> entries, boolean truncated) {
        return new Result(original, normalized, kind, entries, truncated);
    }

    /** Retains case. Unicode punctuation and spaces are presentation differences, not guessed word stems. */
    static String normalize(String value) {
        if (value == null) return "";
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder normalized = new StringBuilder(); boolean space = false;
        for (int code : text.codePoints().toArray()) {
            if (Character.isWhitespace(code) || Character.isSpaceChar(code)) { space = normalized.length() > 0; continue; }
            if (space) { normalized.append(' '); space = false; }
            if (code == 0x2018 || code == 0x2019 || code == 0x02bc) code = '\'';
            if (code == 0x201c || code == 0x201d) code = '"';
            if ((code >= 0x2010 && code <= 0x2015) || code == 0x2212) code = '-';
            normalized.appendCodePoint(code);
        }
        int from = 0, to = normalized.length();
        while (from < to && (normalized.charAt(from) == ' ' || EDGE_PUNCTUATION.indexOf(normalized.charAt(from)) >= 0)) from++;
        while (to > from && (normalized.charAt(to-1) == ' ' || EDGE_PUNCTUATION.indexOf(normalized.charAt(to-1)) >= 0)) to--;
        return normalized.substring(from, to);
    }

    static boolean isLookupText(String value) {
        if (value == null || value.length() > MAX_CHARACTERS || hasLineBreak(value)) return false;
        // Reject common address/code delimiters before punctuation trimming can disguise them.
        if (value.matches("(?s).*[\\\\/@_=+*<>\\[\\]{}|&^%$#~].*") || value.matches("(?s).*[A-Za-z]\\s*\\(.*")) return false;
        String normalized = normalize(value);
        return !normalized.isEmpty() && normalized.split(" ").length <= MAX_WORDS && PHRASE.matcher(normalized).matches();
    }

    private static boolean hasLineBreak(String value) {
        return value.codePoints().anyMatch(code -> code == '\r' || code == '\n' || code == 0x85 || code == 0x2028 || code == 0x2029
                || (Character.isISOControl(code) && code != '\t'));
    }
}
