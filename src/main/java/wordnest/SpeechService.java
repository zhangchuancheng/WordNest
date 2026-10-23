package wordnest;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns asynchronous local speech. A new utterance always replaces the previous one. */
final class SpeechService implements AutoCloseable {
    static final int MAX_TEXT_LENGTH = 4000;
    record Voice(String name, String culture) {
        Voice { Objects.requireNonNull(name); Objects.requireNonNull(culture); }
        @Override public String toString() { return name + " · " + culture; }
    }
    record Request(String text, String voice, int rate) { }
    enum State { READY, SPEAKING, STOPPED, ERROR, CLOSED }
    record Status(State state, String message) { }

    interface Engine extends AutoCloseable {
        List<Voice> voices(Cancellation cancellation) throws Exception;
        void speak(Request request, Cancellation cancellation) throws Exception;
        void saveWave(Request request, Path file, Cancellation cancellation) throws Exception;
        @Override void close();
    }

    /** Binding after cancellation still stops a process that was just starting. */
    static final class Cancellation {
        private boolean cancelled;
        private Runnable action;
        synchronized boolean isCancelled() { return cancelled; }
        synchronized void bind(Runnable action) {
            if (cancelled) action.run(); else this.action = action;
        }
        synchronized void clear() { action = null; }
        synchronized void cancel() {
            cancelled = true;
            if (action != null) { action.run(); action = null; }
        }
        void check() throws InterruptedException {
            if (isCancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Speech cancelled");
        }
    }

    static final class SpeechException extends Exception {
        SpeechException(String message) { super(message); }
    }

    private final Engine engine;
    private final Executor callbacks;
    private final ThreadPoolExecutor workers;
    private final Cancellation inventoryCancellation = new Cancellation();
    private CompletableFuture<List<Voice>> inventory;
    private Consumer<Status> listener = ignored -> { };
    private Cancellation playbackCancellation;
    private Future<?> playback;
    private long generation;
    private boolean closed;

    SpeechService() { this(new WindowsSpeechEngine()); }
    SpeechService(Engine engine) { this(engine, SwingUtilities::invokeLater); }
    SpeechService(Engine engine, Executor callbacks) {
        this.engine = Objects.requireNonNull(engine); this.callbacks = Objects.requireNonNull(callbacks);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "wordnest-speech"); thread.setDaemon(true); return thread;
        });
    }

    synchronized void setListener(Consumer<Status> listener) { this.listener = Objects.requireNonNull(listener); }

    synchronized CompletableFuture<List<Voice>> voices() {
        if (closed) return CompletableFuture.failedFuture(new SpeechException("朗读服务已关闭。"));
        if (inventory == null) inventory = CompletableFuture.supplyAsync(() -> {
            try { return List.copyOf(engine.voices(inventoryCancellation)); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, workers);
        return inventory;
    }

    synchronized void speak(String text, String voice, int rate) {
        if (closed) return;
        cancelPlayback(); long ticket = ++generation;
        if (text == null || text.isBlank()) { publish(ticket, State.ERROR, "没有可朗读的文字。"); return; }
        if (text.length() > MAX_TEXT_LENGTH) { publish(ticket, State.ERROR, "文字过长，请选择一段不超过 4000 字符的内容。"); return; }
        if (voice == null || voice.isBlank()) { publish(ticket, State.ERROR, "未找到可用英文语音。"); return; }
        if (rate < -3 || rate > 3) { publish(ticket, State.ERROR, "语速需在 -3 到 3 之间。"); return; }
        Request request = new Request(text, voice, rate);
        Cancellation cancellation = new Cancellation(); playbackCancellation = cancellation;
        publish(ticket, State.SPEAKING, "正在朗读…");
        playback = workers.submit(() -> {
            try {
                cancellation.check(); engine.speak(request, cancellation); cancellation.check();
                finish(ticket);
                publish(ticket, State.READY, "朗读完成。");
            } catch (InterruptedException | CancellationException cancelled) {
                Thread.currentThread().interrupt();
            } catch (Exception failure) {
                finish(ticket);
                publish(ticket, State.ERROR, friendlyFailure(failure));
            }
        });
    }

    synchronized void stop() {
        if (closed || playbackCancellation == null) return;
        cancelPlayback(); publish(++generation, State.STOPPED, "已停止朗读。");
    }

    private synchronized void finish(long ticket) {
        if (ticket == generation) { playbackCancellation = null; playback = null; }
    }

    private void cancelPlayback() {
        if (playbackCancellation != null) playbackCancellation.cancel();
        if (playback != null) playback.cancel(true);
        playbackCancellation = null; playback = null; workers.purge();
    }

    private void publish(long ticket, State state, String message) {
        callbacks.execute(() -> {
            synchronized (SpeechService.this) {
                if (ticket != generation || (closed && state != State.CLOSED)) return;
                listener.accept(new Status(state, message));
            }
        });
    }

    static String friendlyFailure(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return failure instanceof SpeechException ? failure.getMessage() : "本机朗读暂不可用，请稍后重试。";
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true; cancelPlayback(); inventoryCancellation.cancel();
        if (inventory != null) inventory.cancel(true);
        workers.shutdownNow(); engine.close();
        publish(++generation, State.CLOSED, "朗读服务已关闭。");
    }
}
