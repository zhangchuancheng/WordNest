package wordnest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Full bundled editorial corpus check; no windows or user-data access. */
public final class CuratedCoverageTest {
    private static int assertions;
    private static final Pattern CHINESE = Pattern.compile("[\\x{3400}-\\x{9fff}]");
    public static void main(String[] args) throws Exception {
        LearningRepository learning = LearningRepository.load();
        DictionaryRepository dictionary = DictionaryTestResources.load();
        List<String> words;
        try (var stream = App.class.getResourceAsStream("/curated-words.txt")) {
            check(stream != null, "coverage manifest bundled");
            words = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().toList();
        }
        check(words.size() == 355 && new HashSet<>(words).size() == 355, "355 unique curated heads");
        check(learning.curatedWords() == words.size(), "manifest matches loaded corpus");
        Set<String> english = new HashSet<>();
        int examples = 0;
        for (String word : words) {
            check(dictionary.lookup(word).isPresent(), "base lookup: " + word);
            List<LearningRepository.CuratedItem> items = learning.curated(word);
            check(items.equals(learning.curated(" " + word.toUpperCase(java.util.Locale.ROOT) + " ")), "normalized lookup: " + word);
            for (String kind : List.of("example", "collocation", "synonym")) {
                check(items.stream().filter(i -> i.kind().equals(kind)).count() >= 2, kind + " coverage: " + word);
            }
            check(items.stream().anyMatch(i -> i.kind().equals("derived")), "family coverage: " + word);
            check(items.stream().anyMatch(i -> i.kind().equals("morphology") || i.kind().equals("etymology")), "root coverage: " + word);
            check(items.stream().anyMatch(i -> i.kind().equals("source")), "provenance: " + word);
            for (var item : items) {
                if (Set.of("example", "collocation", "derived", "morphology", "etymology").contains(item.kind())) {
                    check(CHINESE.matcher(item.second()).find(), "Chinese content: " + word + " " + item.kind());
                }
                if (item.kind().equals("synonym")) {
                    check(CHINESE.matcher(item.third()).find(), "Chinese synonym distinction: " + word);
                }
                if (item.kind().equals("example")) {
                    examples++;
                    check(english.add(item.first()), "distinct full example sentence: " + word);
                }
            }
        }
        check(examples >= 710, "at least two original bilingual examples per head");
        for (String[] pair : List.of(new String[]{"organize", "organise"}, new String[]{"recognize", "recognise"},
                new String[]{"behavior", "behaviour"}, new String[]{"labor", "labour"})) {
            check(learning.curated(pair[0]).equals(learning.curated(pair[1])) && !learning.curated(pair[0]).isEmpty(),
                    "bundled spelling alias: " + pair[0]);
        }
        System.out.printf("PASS: %d curated coverage assertions; %d heads / %d bilingual examples%n", assertions, words.size(), examples);
    }
    private static void check(boolean valid, String message) {
        assertions++;
        if (!valid) throw new AssertionError(message);
    }
}
