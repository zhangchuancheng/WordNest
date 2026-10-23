package wordnest;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Transparent reading heuristics, not a grammar checker or a claim of editorial review. */
final class ExampleSelection {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*");
    private static final Pattern TERMINAL = Pattern.compile("[.!?。！？][\\\"'’”)]*$");
    static final int RECOMMENDED_LIMIT = 3;
    static final int MAX_WORDS = 24;
    static final int MAX_CHARACTERS = 180;

    enum SourceKind { CURATED, PRACTICE, CORPUS }

    record Candidate(String english, String chinese, String label, String attribution,
                     String url, String declaredForm, SourceKind kind) {
        Candidate {
            Objects.requireNonNull(english); Objects.requireNonNull(chinese); Objects.requireNonNull(label);
            Objects.requireNonNull(attribution); Objects.requireNonNull(url); Objects.requireNonNull(declaredForm);
            Objects.requireNonNull(kind);
        }
        boolean original() { return kind != SourceKind.CORPUS; }
    }

    /** Every distinct source record and original translation survives duplicate grouping. */
    record Group(Candidate primary, List<Candidate> originals, String matchedForm, List<String> notes,
                 boolean recommended, int wordCount) {
        Group { originals = List.copyOf(originals); notes = List.copyOf(notes); }
    }

    record Result(List<Group> recommended, List<Group> additional, int rawCount) {
        Result { recommended = List.copyOf(recommended); additional = List.copyOf(additional); }
        int uniqueCount() { return recommended.size() + additional.size(); }
    }

    private record Ranked(Candidate source, String matchedForm, int matchRank, int wordCount, List<String> notes) {
        boolean suitable() { return notes.isEmpty(); }
    }

    private static final Comparator<Ranked> ORDER = Comparator
            .comparing((Ranked item) -> !item.suitable())
            .thenComparingInt(Ranked::matchRank)
            .thenComparingInt(item -> item.notes().size())
            .thenComparing(item -> item.source().kind())
            .thenComparingInt(Ranked::wordCount);

    static Result select(String query, List<Candidate> candidates) {
        Objects.requireNonNull(query); Objects.requireNonNull(candidates);
        LinkedHashMap<String, List<Candidate>> grouped = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String key = normalizedSentence(candidate.english());
            // Empty or punctuation-only records must not accidentally consume another record.
            if (key.isEmpty()) key = "\u0000" + grouped.size();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
        }
        List<Group> ordered = new ArrayList<>();
        for (List<Candidate> records : grouped.values()) {
            List<Candidate> originals = List.copyOf(new LinkedHashSet<>(records));
            Ranked best = originals.stream().map(item -> rank(query, item)).min(ORDER).orElseThrow();
            ordered.add(new Group(best.source(), originals, best.matchedForm(), best.notes(), best.suitable(), best.wordCount()));
        }
        // Stable sorting keeps source order when all explicit signals tie.
        ordered.sort((a, b) -> ORDER.compare(rank(query, a.primary()), rank(query, b.primary())));
        List<Group> recommended = new ArrayList<>();
        List<Group> additional = new ArrayList<>();
        for (Group group : ordered) {
            if (group.recommended() && recommended.size() < RECOMMENDED_LIMIT) recommended.add(group);
            else additional.add(group);
        }
        return new Result(recommended, additional, candidates.size());
    }

    private static Ranked rank(String query, Candidate candidate) {
        String english = candidate.english().strip();
        boolean exact = containsWord(english, query);
        boolean declared = !candidate.declaredForm().isBlank() && containsWord(english, candidate.declaredForm());
        String matched = exact ? query.strip() : declared ? candidate.declaredForm().strip() : "";
        int words = wordCount(english);
        List<String> notes = new ArrayList<>();
        if (!exact && !declared) notes.add("未直接出现查询词或来源标注的词形");
        if (candidate.chinese().isBlank()) notes.add("原资料缺少中文译文");
        if (words > MAX_WORDS || english.length() > MAX_CHARACTERS) notes.add("长句，建议结合上下文阅读");
        if (words < 3) notes.add("表达很短，需结合上下文");
        if (!TERMINAL.matcher(english).find()) notes.add("未带句末标点，可能是片段");
        if (english.contains("...") || english.contains("…") || english.contains("\n") || english.contains("\r"))
            notes.add("含省略或分段，需核对完整语境");
        return new Ranked(candidate, matched, exact ? 0 : declared ? 1 : 2, words, List.copyOf(notes));
    }

    /** Treat cosmetic punctuation/spacing as equal, while keeping contractions and word boundaries. */
    static String normalizedSentence(String sentence) {
        String normalized = Normalizer.normalize(sentence, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replace('’', '\'');
        var matches = TOKEN.matcher(normalized);
        List<String> words = new ArrayList<>();
        while (matches.find()) words.add(matches.group());
        return String.join(" ", words);
    }

    static int wordCount(String sentence) {
        var matches = TOKEN.matcher(sentence);
        int count = 0;
        while (matches.find()) count++;
        return count;
    }

    static Pattern wordPattern(String value) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(value.strip()) + "(?![\\p{L}\\p{N}_])");
    }

    static boolean containsWord(String sentence, String value) {
        return !value.isBlank() && wordPattern(value).matcher(sentence).find();
    }
}
