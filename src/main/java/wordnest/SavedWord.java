package wordnest;

import java.util.Objects;

/** A retained dictionary entry, personal note, last save time, and independent favorite state. */
public record SavedWord(WordEntry entry, String note, long savedAt, boolean favorite) {
    /** Existing callers creating a newly saved word still opt in to the favorites list. */
    public SavedWord(WordEntry entry, String note, long savedAt) {
        this(entry, note, savedAt, true);
    }

    public SavedWord {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(note, "note");
        if (savedAt < 0) {
            throw new IllegalArgumentException("savedAt must not be negative");
        }
    }
}
