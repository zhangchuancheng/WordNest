package wordnest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** End-to-end checks for actual bundled corpora; never opens windows or user files. */
public final class OpenLearningTest {
    private static int checks;
    private static final Pattern CJK = Pattern.compile("[\\x{3400}-\\x{9fff}]");
    public static void main(String[] args) throws Exception {
        LearningRepository data = LearningRepository.load();
        DictionaryRepository dictionary = DictionaryRepository.load(App.class.getResourceAsStream("/dictionary.bin.gz"));
        var coverage = LearningCoverage.verify(data, dictionary);
        List<String> words;
        try (var input = App.class.getResourceAsStream("/expanded-coverage.tsv")) {
            words = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines()
                    .skip(1).map(l -> l.split("\t")[0]).toList();
        }
        int examples = 0, practice = 0, origins = 0;
        Set<String> kinds = Set.of("example", "practice", "derived", "synonym", "etymology", "component");
        Set<String> original = new HashSet<>();
        for (String word : words) {
            var rows = data.supplemental(word);
            check(rows.equals(data.supplemental(" " + word.toUpperCase(Locale.ROOT) + " ")), "normalization " + word);
            Set<String> sentences = new HashSet<>();
            for (var r : rows) {
                check(kinds.contains(r.kind()), "known kind");
                check(!r.first().isBlank() && CJK.matcher(r.second()).find(), "real Chinese content " + word);
                check(!r.attribution().isBlank(), "attribution " + word);
                if (r.kind().equals("practice")) {
                    practice++;
                    check(r.attribution().contains("原创") && r.attribution().contains("非考试真题"), "original practice label");
                    check(original.add(r.first()), "distinct original practice");
                    check(contains(r.first(), word), "practice headword " + word);
                } else {
                    check(r.sourceUrl().startsWith("https://"), "source URL " + word);
                    if (r.kind().equals("example")) {
                        examples++;
                        check(sentences.add(r.first().toLowerCase(Locale.ROOT)), "distinct corpus English per word");
                        check(r.attribution().contains("Tatoeba") && r.attribution().contains("CC BY 2.0 FR"), "corpus attribution");
                        String form = word;
                        int start = r.attribution().indexOf("例句词形：");
                        if (start >= 0) form = r.attribution().substring(start + "例句词形：".length());
                        check(contains(r.first(), form), "whole-token match " + word + "/" + form);
                        var ids = Pattern.compile("#(\\d+) ").matcher(r.attribution());
                        int matches = 0;
                        while (ids.find()) {
                            check(r.sourceUrl().contains("/sentences/show/" + ids.group(1)), "attributed ID links"); matches++;
                        }
                        check(matches == 2, "both sentence owners/IDs");
                    } else {
                        origins++;
                        check(r.attribution().contains("维基词典"), "dictionary contribution credit");
                    }
                }
            }
            check(sentences.size() <= 3, "bounded per-head corpus examples");
        }
        check(examples > 14000 && practice == 341 && origins > 6000, "real expansion size");
        check(data.supplemental(null).isEmpty() && data.supplemental("qznotawordqz").isEmpty(), "missing lookup");
        check(data.supplemental("accumulate").stream().anyMatch(i -> i.kind().equals("example") && i.attribution().contains("词形")), "source-attested inflection works");
        check(data.supplemental("allocate").stream().anyMatch(i -> i.kind().equals("practice")), "academic gap filled");
        try { data.supplemental("allocate").clear(); throw new AssertionError("mutable result"); }
        catch (UnsupportedOperationException expected) { checks++; }
        System.out.println("PASS: " + checks + " open-learning assertions; " + examples + " corpus placements; " + practice + " originals");
        coverage.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().toString()))
                .forEach(e -> System.out.println(e.getKey() + "=" + e.getValue()));
    }
    private static boolean contains(String text, String word) {
        return Pattern.compile("(?i)(?<![a-z])" + Pattern.quote(word) + "(?![a-z])").matcher(text).find();
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
