package wordnest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Recomputes coverage from loaded resources, not from a marketing constant. */
final class LearningCoverage {
    private LearningCoverage() { }

    static Properties verify(LearningRepository learning, DictionaryRepository dictionary) throws IOException {
        List<String> lines;
        try (InputStream input = App.class.getResourceAsStream("/expanded-coverage.tsv")) {
            if (input == null) throw new IOException("Expanded coverage manifest missing");
            lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).lines().toList();
        }
        if (lines.isEmpty() || !lines.get(0).startsWith("word\tielts\t")) throw new IOException("Bad coverage header");
        Set<String> words = new HashSet<>();
        int examples = 0, families = 0, roots = 0, synonyms = 0, chineseRoots = 0, complete = 0, added = 0, ielts = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 10) throw new IOException("Bad coverage row");
            String word = fields[0];
            if (!words.add(word) || dictionary.lookup(word).isEmpty()) throw new IOException("Bad coverage head: " + word);
            var original = learning.curated(word);
            var supplemental = learning.supplemental(word);
            var lexical = learning.senses(word);
            int exampleCount = (int) (original.stream().filter(i -> i.kind().equals("example")).count()
                    + supplemental.stream().filter(i -> Set.of("example", "practice").contains(i.kind())).count());
            boolean family = original.stream().anyMatch(i -> i.kind().equals("derived"))
                    || supplemental.stream().anyMatch(i -> i.kind().equals("derived"))
                    || lexical.stream().anyMatch(i -> !i.derivations().isEmpty());
            boolean chineseRoot = original.stream().anyMatch(i -> Set.of("morphology", "etymology").contains(i.kind()))
                    || supplemental.stream().anyMatch(i -> Set.of("etymology", "component").contains(i.kind()));
            boolean root = chineseRoot || !learning.origins(word).isEmpty();
            boolean synonym = original.stream().anyMatch(i -> i.kind().equals("synonym"))
                    || supplemental.stream().anyMatch(i -> i.kind().equals("synonym"))
                    || lexical.stream().anyMatch(i -> !i.synonyms().isEmpty());
            int[] actual = {exampleCount, family ? 1 : 0, root ? 1 : 0, synonym ? 1 : 0,
                    chineseRoot ? 1 : 0, supplemental.isEmpty() ? 0 : 1};
            for (int i = 0; i < actual.length; i++) {
                if (!Integer.toString(actual[i]).equals(fields[i + 4])) {
                    throw new IOException("Coverage mismatch: " + word + " column " + (i + 4));
                }
            }
            if (fields[1].equals("1")) ielts++;
            if (fields[1].equals("1") && Math.min(rank(fields[2]), rank(fields[3])) <= 5000 && exampleCount == 0) {
                throw new IOException("Missing bilingual example for common IELTS-tagged word: " + word);
            }
            if (exampleCount > 0) examples++;
            if (family) families++;
            if (root) roots++;
            if (synonym) synonyms++;
            if (chineseRoot) chineseRoots++;
            if (!supplemental.isEmpty()) added++;
            if (exampleCount > 0 && family && root && synonym) complete++;
        }
        if (words.size() < 7500 || examples < 6000 || complete < 4000 || added != learning.supplementalWords()) {
            throw new IOException("Expansion coverage is incomplete");
        }
        Properties result = new Properties();
        result.setProperty("learning.selected.words", Integer.toString(words.size()));
        result.setProperty("learning.selected.ielts", Integer.toString(ielts));
        result.setProperty("learning.supplement.words", Integer.toString(added));
        result.setProperty("learning.bilingual.words", Integer.toString(examples));
        result.setProperty("learning.family.words", Integer.toString(families));
        result.setProperty("learning.root.words", Integer.toString(roots));
        result.setProperty("learning.synonym.words", Integer.toString(synonyms));
        result.setProperty("learning.chinese.root.words", Integer.toString(chineseRoots));
        result.setProperty("learning.four.sections", Integer.toString(complete));
        return result;
    }
    private static int rank(String value) throws IOException {
        if (value.isEmpty()) return Integer.MAX_VALUE;
        try {
            int number = Integer.parseInt(value);
            return number > 0 ? number : Integer.MAX_VALUE;
        } catch (NumberFormatException e) { throw new IOException("Invalid frequency rank", e); }
    }
}
