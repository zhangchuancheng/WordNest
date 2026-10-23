package wordnest;

import java.io.IOException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Coordinates navigation with pending-note saving; has no UI or desktop dependencies. */
final class LookupSession {
    record Result(boolean opened, String warning) { }
    private final LookupHistory history;
    private final Function<String, Optional<WordEntry>> lookup;
    private final BooleanSupplier saveNote;
    private final Consumer<WordEntry> display;

    LookupSession(LookupHistory history, Function<String, Optional<WordEntry>> lookup,
                  BooleanSupplier saveNote, Consumer<WordEntry> display) {
        this.history = history;
        this.lookup = lookup;
        this.saveNote = saveNote;
        this.display = display;
    }

    Result visit(WordEntry entry, boolean remember) {
        if (!saveNote.getAsBoolean()) return new Result(false, "");
        String warning = "";
        if (remember) {
            try { history.record(entry.word()); }
            catch (IOException e) {
                history.recordSession(entry.word());
                warning = "查词成功，历史记录暂未能保存；本次仍可前进、后退";
            }
        } else history.seedSession(entry.word());
        display.accept(entry);
        return new Result(true, warning);
    }

    Result back() { return move(false); }
    Result forward() { return move(true); }

    /** A failed lookup page is not a history visit; return to the last successful word first. */
    Result redisplayCurrent() {
        Optional<WordEntry> entry = history.current().flatMap(lookup);
        if (entry.isEmpty()) return new Result(false, "当前历史词条不在词库中");
        if (!saveNote.getAsBoolean()) return new Result(false, "");
        display.accept(entry.get());
        return new Result(true, "");
    }

    private Result move(boolean forward) {
        Optional<String> target = forward ? history.peekForward() : history.peekBack();
        if (target.isEmpty()) return new Result(false, "");
        Optional<WordEntry> entry = lookup.apply(target.get());
        if (entry.isEmpty()) return new Result(false, "该历史词条不在当前词库中，已保留当前内容");
        if (!saveNote.getAsBoolean()) return new Result(false, "");
        boolean committed = forward ? history.commitForward(target.get()) : history.commitBack(target.get());
        if (!committed) return new Result(false, "历史位置已改变，请重试");
        display.accept(entry.get());
        return new Result(true, "");
    }
}
