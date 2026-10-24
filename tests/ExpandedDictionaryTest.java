package wordnest;

import java.nio.file.*;
import java.util.*;

/** Real packaged data acceptance and bounded lookup timings; never opens windows or personal data. */
public final class ExpandedDictionaryTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass an isolated test cache directory");
        Path cache = Path.of(args[0]);
        Runtime runtime = Runtime.getRuntime();
        System.gc(); long before = runtime.totalMemory() - runtime.freeMemory();
        long start = System.nanoTime();
        DictionaryRepository dictionary = DictionaryRepository.loadBundled(cache);
        double loadSeconds = seconds(start);
        LookupResolver resolver = LookupResolver.loadBundled(dictionary);
        LearningRepository learning = LearningRepository.load();
        System.gc(); long retained = runtime.totalMemory() - runtime.freeMemory() - before;
        check(dictionary.size() == 3_403_176, "Exact deduplicated coverage");
        check(learning.curatedWords() == 355, "Original learning cards remain available");
        for (String word : List.of("environment", "take", "relevant", "certificate", "algorithm")) {
            WordEntry entry = dictionary.lookup(word).orElseThrow();
            check(!entry.tags().contains("wn-expanded"), "Base record wins: " + word);
            check(!entry.translation().isBlank(), "Base translation retained: " + word);
        }
        for (String word : List.of("tokenization", "microplastics", "took into account")) {
            WordEntry entry = dictionary.lookup(word).orElseThrow();
            check(entry.tags().contains("wn-expanded"), "New record source is visible: " + word);
            check(!entry.translation().isBlank(), "New record preserves available translation: " + word);
        }
        check(resolver.resolve("took into account").kind() == LookupResolver.MatchKind.EXACT,
                "An inflected phrase already in the expanded source wins over inferred alternatives");
        for (String[] query : new String[][]{
                {"environment,", "environment"}, {"in\u00a0 terms of", "in terms of"},
                {"well\u2013preserved", "well-preserved"}, {"don\u2019t", "don't"},
                {"takes a back seat", "take a back seat"}, {"making a bad start", "make a bad start"}}) {
            LookupResolver.Result result = resolver.resolve(query[0]);
            check(result.hasSingleMatch(), "One valid match for " + query[0] + ": " + result);
            check(result.candidates().get(0).word().equals(query[1]), "Correct target for " + query[0]);
        }
        check(resolver.resolve("takes a back seat").kind() == LookupResolver.MatchKind.INFLECTION,
                "Source-recorded phrase inflection fallback works");
        check(resolver.resolve("zzzxqvlookupnotaword").candidates().isEmpty(), "No invented definitions");
        List<String> suggestions = dictionary.suggest("carbon foot", 5);
        check(suggestions.contains("carbon footprint"), "Expanded prefix search stops at a real matching prefix");
        check(suggestions.stream().allMatch(word -> word.toLowerCase(Locale.ROOT).startsWith("carbon foot")), "Prefix boundary");
        start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            check(dictionary.lookup(i % 2 == 0 ? "tokenization" : "environment").isPresent(), "Indexed lookup");
            check(resolver.resolve(i % 2 == 0 ? "takes a back seat" : "in  terms of").hasSingleMatch(), "Resolved lookup");
        }
        double lookupSeconds = seconds(start);
        start = System.nanoTime();
        DictionaryRepository warmed = DictionaryRepository.loadBundled(cache);
        double warmSeconds = seconds(start);
        check(warmed.size() == dictionary.size(), "Verified cache reopens while earlier map remains live");
        check(warmed.lookup("tokenization").equals(dictionary.lookup("tokenization")), "Same decoded entry from cache");
        System.out.printf(Locale.ROOT,
                "Full WND2: %,d entries; load %.3f s; warm %.3f s; dictionary+resolver+learning retained heap %.1f MiB; 2000 lookups %.3f s%n",
                dictionary.size(), loadSeconds, warmSeconds, retained / 1048576.0, lookupSeconds);
        System.out.println("PASS: " + assertions + " real expanded dictionary assertions; no windows or user data");
    }
    private static double seconds(long start) { return (System.nanoTime() - start) / 1_000_000_000.0; }
    private static void check(boolean value, String message) {
        assertions++; if (!value) throw new AssertionError(message);
    }
}
