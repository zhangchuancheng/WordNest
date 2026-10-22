package wordnest;

import java.util.Objects;

/** A dictionary entry with a personal note and its last save time in epoch milliseconds. */
public record SavedWord(WordEntry entry, String note, long savedAt) {
    public SavedWord {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(note, "note");
        if (savedAt < 0) {
            throw new IllegalArgumentException("savedAt must not be negative");
        }
    }
}
