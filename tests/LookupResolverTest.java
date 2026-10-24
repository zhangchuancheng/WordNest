package wordnest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Synthetic dictionary and documented form pairs; no desktop, network or unbounded dictionary scan. */
public final class LookupResolverTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        exactAndNormalization(); recordedForms(); ambiguityAndLimits(); inputDataValidation();
        System.out.println("PASS: " + assertions + " lookup resolver assertions; synthetic dictionary and recorded forms only");
    }

    private static void exactAndNormalization() throws Exception {
        LookupResolver resolver = resolver("us\twe\ntaken\ttake\n", "US", "we", "taken", "take", "take into account", "can't", "well-being", "C++", "U.S.");
        check(resolver.resolve("US").kind() == LookupResolver.MatchKind.EXACT && word(resolver.resolve("US")).equals("US"), "Literal existing uppercase entry is considered before any form mapping");
        check(word(resolver.resolve("taken")).equals("taken") && resolver.resolve("taken").kind() == LookupResolver.MatchKind.EXACT, "Existing inflected headword wins over a lemma fallback");
        check(word(resolver.resolve("C++")).equals("C++") && word(resolver.resolve("U.S.")).equals("U.S."), "Manual exact lookup preserves meaningful source punctuation");
        for (String input : List.of("“take\u00a0into   account.”", "(take\tinto account)", "  take  into account  ")) {
            var result = resolver.resolve(input);
            check(result.kind() == LookupResolver.MatchKind.NORMALIZED && word(result).equals("take into account"), "Whitespace and wrapper punctuation normalize without changing definition: " + input);
        }
        check(word(resolver.resolve("can’t")).equals("can't"), "Typographic apostrophe normalizes inside a word");
        check(word(resolver.resolve("well‑being")).equals("well-being"), "Nonbreaking hyphen normalizes inside a word");
        check(LookupResolver.normalize(" ‘US’ ").equals("US"), "Normalizer retains capitalization");
        check(resolver.resolve(null).kind() == LookupResolver.MatchKind.INVALID, "Missing query is rejected");
        check(resolver.resolve("\nword").kind() == LookupResolver.MatchKind.INVALID, "Leading newline is not silently converted into a lookup");
        check(resolver.resolve("x".repeat(121)).kind() == LookupResolver.MatchKind.INVALID, "Oversize manual query is bounded");
    }

    private static void recordedForms() throws Exception {
        LookupResolver resolver = resolver("took\ttake\ntakes\ttake\naccounts\taccount\nwent\tgo\nwent\tgo\n", "take", "go", "take into account", "wuggle");
        check(word(resolver.resolve("took")).equals("take") && resolver.resolve("took").kind() == LookupResolver.MatchKind.INFLECTION, "Recorded irregular form finds its existing lemma");
        check(word(resolver.resolve("took into account")).equals("take into account"), "A phrase may replace one source-recorded token");
        check(word(resolver.resolve("takes into accounts")).equals("take into account"), "Multiple mapped tokens can resolve one actual phrase headword");
        check(word(resolver.resolve("“TOOK into account!”")).equals("take into account"), "Case and punctuation do not block a documented fallback");
        check(resolver.resolve("went").candidates().size() == 1, "Duplicate source pairs do not invent ambiguity");
        check(resolver.resolve("wuggles").kind() == LookupResolver.MatchKind.NOT_FOUND, "Unknown suffix is never guessed by stemming");
        check(resolver.resolve("took into consideration").kind() == LookupResolver.MatchKind.NOT_FOUND, "Mapped tokens do not fabricate a phrase absent from the dictionary");
        var result = resolver.resolve("took");
        check(result.original().equals("took") && result.normalized().equals("took"), "Result retains the source query for honest feedback");
        check(result.candidates().get(0).translation().equals("source:take"), "Definition is the original dictionary value");
        try { result.candidates().clear(); throw new AssertionError("Expected immutable result"); }
        catch (UnsupportedOperationException expected) { check(true, "Result candidates are immutable"); }
    }

    private static void ambiguityAndLimits() throws Exception {
        LookupResolver resolver = resolver("axes\taxe\naxes\taxis\n", "axe paper", "axis paper");
        var result = resolver.resolve("axes paper");
        check(result.kind() == LookupResolver.MatchKind.AMBIGUOUS && !result.hasSingleMatch(), "Multiple valid lemmas are presented instead of selecting one");
        check(result.candidates().stream().map(WordEntry::word).toList().equals(List.of("axe paper", "axis paper")), "Every real candidate remains distinguishable and ordered");
        Map<String, List<String>> forms = new HashMap<>(); List<String> alternatives = new ArrayList<>();
        for (char letter = 'a'; letter <= 'z'; letter++) alternatives.add(Character.toString(letter));
        forms.put("forms", alternatives);
        LookupResolver bounded = new LookupResolver(dictionary("a a"), forms);
        result = bounded.resolve("forms forms");
        check(result.truncated() && result.candidates().size() == 1 && !result.hasSingleMatch(), "An incomplete bounded search never declares its single found candidate unique");
        check(result.kind() == LookupResolver.MatchKind.AMBIGUOUS, "Truncated search requires an explicit candidate choice");
        LookupResolver capped = new LookupResolver(dictionary(alternatives.toArray(String[]::new)), forms);
        result = capped.resolve("forms");
        check(result.candidates().size() == LookupResolver.MAX_CANDIDATES && result.truncated(), "Large ambiguous candidate lists are capped with an explicit incomplete flag");
    }

    private static void inputDataValidation() throws Exception {
        for (String content : List.of("one\ttwo\tthree\n", "word\t\n", "word\tother phrase\n", "word123\tword\n", "word\t<script>\n")) {
            try { resolver(content, "word"); throw new AssertionError("Expected malformed form data rejection"); }
            catch (IOException expected) { check(true, "Malformed mapping does not silently add unsafe or inferred entries"); }
        }
        byte[] gzip = compressed(new byte[]{(byte)0xff, '\t', 'a'});
        try { LookupResolver.load(dictionary("a"), new ByteArrayInputStream(gzip)); throw new AssertionError("Expected malformed UTF-8 rejection"); }
        catch (IOException expected) { check(true, "Mapping text must be valid UTF-8"); }
        check(LookupResolver.withoutInflections(dictionary("word")).resolve("word").kind() == LookupResolver.MatchKind.EXACT,
                "A missing optional form resource can degrade to safe exact lookup");
    }

    static LookupResolver resolver(String rows, String... words) throws Exception {
        return LookupResolver.load(dictionary(words), new ByteArrayInputStream(compressed(rows.getBytes(StandardCharsets.UTF_8))));
    }
    static DictionaryRepository dictionary(String... words) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeInt(0x574e4431); out.writeInt(words.length);
            for (String word : words) for (String value : List.of(word, "", "source:" + word, "definition:" + word, "", "")) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
            }
        }
        return DictionaryRepository.load(new ByteArrayInputStream(compressed(raw.toByteArray())));
    }
    private static byte[] compressed(byte[] value) throws IOException { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) { gzip.write(value); } return bytes.toByteArray(); }
    private static String word(LookupResolver.Result result) { if (!result.hasSingleMatch()) throw new AssertionError("Expected unique match: " + result); return result.candidates().get(0).word(); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
