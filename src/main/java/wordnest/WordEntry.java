package wordnest;

import java.util.Objects;

/** An immutable dictionary entry. Definitions may contain real newline characters. */
public record WordEntry(String word, String phonetic, String translation,
                        String definition, String tags, String exchange) {
    public WordEntry {
        Objects.requireNonNull(word, "word");
        Objects.requireNonNull(phonetic, "phonetic");
        Objects.requireNonNull(translation, "translation");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(exchange, "exchange");
    }
}
