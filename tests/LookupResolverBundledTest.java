package wordnest;

import java.nio.file.Path;
import java.util.List;

/** Actual bundled dictionary and source-recorded forms; cache lives under the supplied test path. */
public final class LookupResolverBundledTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        DictionaryRepository dictionary = DictionaryRepository.loadBundled(Path.of(args[0]));
        LookupResolver resolver = LookupResolver.loadBundled(dictionary);
        for (String query : List.of("US", "research", "take into account", "took into account", "takes into account", "taking into account",
                "taken into account", "take into accounts", "takes into accounts", "took into accounts", "taking into accounts", "‘take\u00a0into  account.’", "can’t", "well‑being", "wordnestimaginaryphrasezzzz")) {
            var result = resolver.resolve(query);
            System.out.println(query + " -> " + result.kind() + " " + result.candidates().stream().map(WordEntry::word).toList());
            var literal = dictionary.lookup(query.strip());
            if (literal.isPresent()) check(result.kind() == LookupResolver.MatchKind.EXACT && result.candidates().equals(List.of(literal.get())),
                    "Actual dictionary exact match always wins: " + query);
        }
        var phrase = resolver.resolve("take into accounts");
        check(phrase.hasSingleMatch() && phrase.candidates().get(0).word().equalsIgnoreCase("take into account"), "Actual stored accounts mapping resolves a known phrase");
        var ambiguous = resolver.resolve("takes into accounts");
        check(ambiguous.kind() == LookupResolver.MatchKind.AMBIGUOUS && !ambiguous.hasSingleMatch(), "Actual expanded headwords keep partially and fully mapped alternatives explicit");
        var normalized = resolver.resolve("‘take\u00a0into  account.’");
        check(normalized.kind() == LookupResolver.MatchKind.NORMALIZED && normalized.candidates().get(0).word().equalsIgnoreCase("take into account"), "Actual phrase supports Unicode wrappers and nonbreaking spaces");
        check(resolver.resolve("wordnestimaginaryphrasezzzz").kind() == LookupResolver.MatchKind.NOT_FOUND, "Unknown word is not fabricated in the full dictionary");
        System.out.printf("PASS: %d bundled resolver assertions; %,d entries; %.3f s dictionary plus resolver load and sample queries%n",
                assertions, dictionary.size(), (System.nanoTime() - start) / 1_000_000_000.0);
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
