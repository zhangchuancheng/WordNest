package wordnest;

import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.List;
import java.util.HashSet;

/** Checks the native package without creating windows or accessing user data. */
final class PackageCheck {
    private PackageCheck() { }

    static int run(String reportFile) {
        Properties report = new Properties();
        report.setProperty("java.version", System.getProperty("java.version"));
        report.setProperty("java.home", System.getProperty("java.home"));
        report.setProperty("os.arch", System.getProperty("os.arch"));
        report.setProperty("headless", System.getProperty("java.awt.headless", "false"));
        int result = 0;
        try {
            Path cache = Path.of(reportFile).toAbsolutePath().getParent().resolve("dictionary-check-cache");
            long loadStarted = System.nanoTime();
            DictionaryRepository dictionary = DictionaryRepository.loadBundled(cache);
            report.setProperty("dictionary.load.seconds", String.format(java.util.Locale.ROOT, "%.3f",
                    (System.nanoTime() - loadStarted) / 1_000_000_000.0));
            report.setProperty("dictionary.cache", cache.toString());
            if (dictionary.size() != 3_403_176) throw new IllegalStateException("Expanded dictionary count mismatch");
            if (!dictionary.lookup("tokenization").orElseThrow().tags().contains("wn-expanded"))
                throw new IllegalStateException("Expanded dictionary source marker missing");
            LookupResolver resolver = LookupResolver.loadBundled(dictionary);
            for (String[] probe : new String[][]{{"in  terms of", "in terms of"}, {"environment,", "environment"},
                    {"well\u2013preserved", "well-preserved"}, {"takes a back seat", "take a back seat"}}) {
                LookupResolver.Result match = resolver.resolve(probe[0]);
                if (!match.hasSingleMatch() || !match.candidates().get(0).word().equalsIgnoreCase(probe[1]))
                    throw new IllegalStateException("Lookup probe failed: " + probe[0]);
            }
            if (!ClipboardWatcher.isLookupText("look forward to") || ClipboardWatcher.isLookupText("https://example.org"))
                throw new IllegalStateException("Phrase clipboard filter failed");
            report.setProperty("lookup.normalization.phrases.inflections", "PASS");
            WordEntry sample = dictionary.lookup("algorithm").orElseThrow();
            if (sample.translation().isBlank() || sample.phonetic().isBlank() || sample.definition().isBlank()) {
                throw new IllegalStateException("Dictionary sample is incomplete");
            }
            if (App.class.getResource("/app.ico") == null || App.class.getResource("/app.png") == null)
                throw new IllegalStateException("Icon resource is missing");
            java.awt.image.BufferedImage smallIcon = MainWindow.icon(16);
            if (smallIcon.getWidth() != 16 || smallIcon.getHeight() != 16 || (smallIcon.getRGB(0, 0) >>> 24) != 0)
                throw new IllegalStateException("The new tray icon is missing or malformed");
            if (App.class.getResource("/speech/windows-speech.ps1") == null)
                throw new IllegalStateException("Local Windows speech bridge is missing");
            report.setProperty("speech.bridge", "bundled; voice availability requires Windows runtime check");
            report.setProperty("dictionary.entries", Integer.toString(dictionary.size()));
            report.setProperty("sample.word", sample.word());
            report.setProperty("sample.translation", sample.translation());
            report.setProperty("sample.phonetic", sample.phonetic());
            LearningRepository learning = LearningRepository.load();
            if (learning.senses("relevant").isEmpty() || learning.origins("relevant").isEmpty()
                    || learning.curated("relevant").stream().noneMatch(item -> item.kind().equals("example"))) {
                throw new IllegalStateException("Learning resources are missing or incomplete");
            }
            report.setProperty("learning.lexical.words", Integer.toString(learning.lexicalWords()));
            report.setProperty("learning.origin.words", Integer.toString(learning.originWords()));
            report.setProperty("learning.curated.words", Integer.toString(learning.curatedWords()));
            List<String> curatedWords;
            try (InputStream words = App.class.getResourceAsStream("/curated-words.txt")) {
                if (words == null) throw new IllegalStateException("Curated coverage manifest missing");
                curatedWords = new BufferedReader(new InputStreamReader(words, StandardCharsets.UTF_8))
                        .lines().filter(line -> !line.isBlank()).toList();
            }
            if (curatedWords.size() != 355 || new HashSet<>(curatedWords).size() != 355
                    || learning.curatedWords() != curatedWords.size()) {
                throw new IllegalStateException("Curated learning coverage does not match version 0.3");
            }
            int examples = 0, families = 0, synonyms = 0;
            for (String word : curatedWords) {
                if (dictionary.lookup(word).isEmpty()) throw new IllegalStateException("Missing headword: " + word);
                var items = learning.curated(word);
                long exampleCount = items.stream().filter(i -> i.kind().equals("example")).count();
                long familyCount = items.stream().filter(i -> i.kind().equals("derived")).count();
                long synonymCount = items.stream().filter(i -> i.kind().equals("synonym")).count();
                boolean roots = items.stream().anyMatch(i -> i.kind().equals("morphology") || i.kind().equals("etymology"));
                if (exampleCount < 2 || familyCount < 1 || synonymCount < 2 || !roots) {
                    throw new IllegalStateException("Incomplete four-section learning card: " + word);
                }
                examples += (int) exampleCount; families += (int) familyCount; synonyms += (int) synonymCount;
            }
            report.setProperty("learning.curated.examples", Integer.toString(examples));
            report.setProperty("learning.curated.families", Integer.toString(families));
            report.setProperty("learning.curated.synonyms", Integer.toString(synonyms));
            report.setProperty("learning.curated.four.sections", Integer.toString(curatedWords.size()));
            report.putAll(LearningCoverage.verify(learning, dictionary));
            report.putAll(Theme.verify());
            report.setProperty("app.version", "0.7.4");
            report.setProperty("status", "PASS");
        } catch (Exception e) {
            report.setProperty("status", "FAIL");
            report.setProperty("error", e.toString());
            result = 1;
        }
        try {
            Path target = Path.of(reportFile).toAbsolutePath();
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                report.store(writer, "WordNest package check; no windows or personal data accessed");
            }
        } catch (Exception e) {
            System.err.println("Cannot write package check report: " + e.getMessage());
            return 2;
        }
        return result;
    }
}
