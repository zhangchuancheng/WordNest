package wordnest;

import java.util.List;
import java.util.Locale;

/** Offline selection tests use synthetic records; no network, windows or user data. */
public final class ExampleSelectionTest {
    private static int assertions;

    public static void main(String[] args) {
        rankingAndReasons();
        learningMaterialPriority();
        duplicatesRetainEvidence();
        conservativeMatching();
        fallbackAndLimits();
        System.out.println("PASS: " + assertions + " example selection assertions");
    }

    private static void rankingAndReasons() {
        var longSentence = candidate("We run through " + "many different neighborhoods ".repeat(10) + "before breakfast.", "长句原译文", "run", "long");
        var shortSentence = candidate("We run every morning.", "我们每天早上跑步。", "run", "short");
        var declaredForm = candidate("They ran to school yesterday.", "他们昨天跑去学校。", "ran", "declared");
        var noMatch = candidate("Jogging keeps us healthy.", "慢跑使我们健康。", "run", "no-match");
        var fragment = candidate("a run in the park", "公园里的一次跑步", "run", "fragment");
        var incomplete = candidate("We run ... and then", "我们跑步……然后", "run", "ellipsis");
        var result = ExampleSelection.select("run", List.of(longSentence, noMatch, fragment, incomplete, declaredForm, shortSentence));
        check(result.recommended().stream().map(g -> g.primary().url()).toList().equals(List.of("short", "declared")),
                "Short direct sentence and explicitly recorded inflection outrank other records");
        check(result.recommended().get(1).matchedForm().equals("ran"), "Recorded form survives for display and highlighting");
        check(result.additional().size() == 4, "All downgraded examples remain accessible");
        check(result.additional().stream().filter(g -> g.primary().equals(longSentence)).findFirst().orElseThrow()
                .notes().stream().anyMatch(n -> n.contains("长句")), "Long sentence has an explainable reason");
        check(result.additional().stream().filter(g -> g.primary().equals(fragment)).findFirst().orElseThrow()
                .notes().stream().anyMatch(n -> n.contains("句末标点")), "Possible fragment is labelled without claiming a grammar judgement");
        check(result.additional().stream().filter(g -> g.primary().equals(noMatch)).findFirst().orElseThrow()
                .matchedForm().isEmpty(), "Absent form is not invented for highlighting");
        check(result.rawCount() == 6 && result.uniqueCount() == 6, "Counts distinguish records from displayed groups");
    }

    private static void learningMaterialPriority() {
        var generic = candidate("That would be relevant.", "那可能有关。", "relevant", "corpus");
        var learning = new ExampleSelection.Candidate("Her experience with databases is relevant to this internship.",
                "她使用数据库的经历与这份实习有关。", "学习卡", "Original learning card", "curated", "relevant", ExampleSelection.SourceKind.CURATED);
        var practice = new ExampleSelection.Candidate("This example is relevant to our discussion.",
                "这个例子与讨论有关。", "学习练习", "Original practice", "practice", "relevant", ExampleSelection.SourceKind.PRACTICE);
        var tooLong = new ExampleSelection.Candidate("Relevant " + "background information ".repeat(30) + "follows.",
                "长句译文。", "学习卡", "Original long card", "long", "relevant", ExampleSelection.SourceKind.CURATED);
        var result = ExampleSelection.select("relevant", List.of(generic, tooLong, practice, learning));
        check(result.recommended().stream().map(g -> g.primary().url()).toList().equals(List.of("curated", "practice", "corpus")),
                "Eligible learning cards and practice precede a shorter generic corpus sentence");
        check(result.additional().get(0).primary().equals(tooLong), "Original status cannot promote a sentence that fails the length conditions");
    }

    private static void duplicatesRetainEvidence() {
        var first = candidate("We run every morning.", "我们每天早上跑步。", "run", "first-author-and-id");
        var second = candidate("  WE  RUN, every morning!  ", "我們每個早晨都跑步！", "run", "second-author-and-id");
        var third = candidate("We run every morning?", "我们每个早晨都跑步吗？", "run", "third-author-and-id");
        var result = ExampleSelection.select("run", List.of(first, second, third, first));
        check(result.uniqueCount() == 1 && result.recommended().size() == 1, "Case, whitespace and sentence punctuation variants use one card");
        var group = result.recommended().get(0);
        check(group.originals().equals(List.of(first, second, third)), "Distinct originals and translations remain verbatim; exact duplicate record is removed");
        check(group.primary().english().equals("We run every morning."), "Selection does not rewrite the display sentence");
        check(group.originals().stream().map(ExampleSelection.Candidate::chinese).distinct().count() == 3,
                "Alternative translations are retained rather than merged or overwritten");
        check(result.rawCount() == 4, "Original record count includes duplicates");
        expectImmutable(() -> group.originals().clear(), "Original evidence list is immutable");
        expectImmutable(() -> result.recommended().clear(), "Selection cannot be mutated by the UI");
    }

    private static void conservativeMatching() {
        check(ExampleSelection.containsWord("We RUN, every day.", "run"), "Case and punctuation allow exact word matching");
        check(!ExampleSelection.containsWord("Running with a runner.", "run"), "Substring is not a direct match");
        check(ExampleSelection.containsWord("The a.b entry is here.", "a.b"), "Query regex metacharacters are quoted");
        check(!ExampleSelection.containsWord("The axb entry is here.", "a.b"), "Regex metacharacter does not become a wildcard");
        check(!ExampleSelection.containsWord("We run.", " "), "Blank query matches nothing");
        check(!ExampleSelection.normalizedSentence("We can't run.").equals(ExampleSelection.normalizedSentence("We cant run.")),
                "Contractions retain potentially meaningful punctuation");
        check(!ExampleSelection.normalizedSentence("an ice cream").equals(ExampleSelection.normalizedSentence("a nice cream")),
                "Word boundaries are not removed during deduplication");
        check(ExampleSelection.normalizedSentence("We can’t run.").equals(ExampleSelection.normalizedSentence("WE CAN'T RUN!")),
                "Typographic apostrophes normalize consistently");
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            check(ExampleSelection.normalizedSentence("I RUN.").equals("i run"), "Deduplication is independent of host locale");
        } finally { Locale.setDefault(saved); }
        var unknownInflection = ExampleSelection.select("run", List.of(candidate("We ran to school.", "我们跑去学校了。", "run", "source")));
        check(unknownInflection.recommended().isEmpty(), "Unrecorded irregular inflection is not inferred");
    }

    private static void fallbackAndLimits() {
        var result = ExampleSelection.select("run", List.of(
                candidate("Run!", "跑！", "run", "short"),
                candidate("a run in the park", "公园里的一次跑步", "run", "fragment")));
        check(result.recommended().isEmpty() && result.additional().size() == 2,
                "All low-signal records remain available for the UI's labelled fallback");
        var many = ExampleSelection.select("run", List.of(
                candidate("We run every Monday.", "周一跑。", "run", "one"),
                candidate("We run every Tuesday.", "周二跑。", "run", "two"),
                candidate("We run every Wednesday.", "周三跑。", "run", "three"),
                candidate("We run every Thursday.", "周四跑。", "run", "four")));
        check(many.recommended().size() == 3 && many.additional().size() == 1, "Recommendation list is capped at three without dropping remaining good sentences");
        check(many.recommended().get(0).primary().url().equals("one"), "Equivalent signals preserve source order");
        var noTranslation = ExampleSelection.select("run", List.of(candidate("We run every morning.", "", "run", "empty")));
        check(noTranslation.recommended().isEmpty() && noTranslation.additional().size() == 1, "Missing translation is retained but not advertised as recommended bilingual material");
        var empty = ExampleSelection.select("run", List.of());
        check(empty.uniqueCount() == 0 && empty.rawCount() == 0, "Empty corpus remains empty");
    }

    private static ExampleSelection.Candidate candidate(String english, String chinese, String form, String source) {
        return new ExampleSelection.Candidate(english, chinese, "语料", "Author " + source, source, form, ExampleSelection.SourceKind.CORPUS);
    }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void expectImmutable(Runnable change, String message) {
        try { change.run(); } catch (UnsupportedOperationException expected) { assertions++; return; }
        throw new AssertionError(message);
    }
}
