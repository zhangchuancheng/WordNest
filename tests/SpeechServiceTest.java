package wordnest;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** No sounds, windows or system voices: deterministic fake engine lifecycle tests. */
public final class SpeechServiceTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        asynchronousReplacement(); failureAndValidation(); queuedCallbacks();
        System.out.println("PASS: " + assertions + " speech service assertions; fake engine only; no audio");
    }

    private static void asynchronousReplacement() throws Exception {
        FakeEngine engine = new FakeEngine(); List<SpeechService.Status> events = new CopyOnWriteArrayList<>();
        try (SpeechService service = new SpeechService(engine, Runnable::run)) {
            service.setListener(events::add);
            check(service.voices().get(2, TimeUnit.SECONDS).size() == 1, "Installed inventory exposed asynchronously");
            check(service.voices().get(2, TimeUnit.SECONDS).size() == 1 && engine.inventories == 1, "Inventory is cached");
            long start = System.nanoTime(); service.speak("first", "English", -3);
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250, "Speak does not block caller");
            waitFor(() -> engine.started.contains("first"));
            service.speak("second", "English", 3);
            waitFor(() -> engine.started.contains("second") && engine.cancelled.contains("first"));
            check(engine.cancelled.contains("first"), "New speech promptly cancels the previous utterance");
            check(engine.requests.stream().anyMatch(r -> r.text().equals("second") && r.rate() == 3 && r.voice().equals("English")),
                    "Voice, text and rate reach the engine unchanged");
            service.stop(); waitFor(() -> engine.cancelled.contains("second"));
            check(events.get(events.size()-1).state() == SpeechService.State.STOPPED, "Stop publishes an honest final state");
            service.speak("complete", "English", 0);
            waitFor(() -> events.get(events.size()-1).state() == SpeechService.State.READY);
            check(engine.started.contains("complete"), "Service remains usable after cancellation");
            int eventCount = events.size(); service.stop();
            check(events.size() == eventCount, "Stop while idle preserves the current ready state");
            service.speak("closing", "English", 0); waitFor(() -> engine.started.contains("closing"));
            start = System.nanoTime(); service.close();
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 250, "Close returns promptly without waiting on a worker");
            check(engine.closed && events.get(events.size()-1).state() == SpeechService.State.CLOSED, "Close disposes engine and notifies status");
            service.speak("after close", "English", 0);
            check(!engine.started.contains("after close"), "Closed service cannot restart playback");
        }
        SpeechService.Cancellation token = new SpeechService.Cancellation(); token.cancel();
        boolean[] cancelled = {false}; token.bind(() -> cancelled[0] = true);
        check(cancelled[0], "Cancellation before process binding still stops the newly created process");
    }

    private static void failureAndValidation() throws Exception {
        FakeEngine engine = new FakeEngine(); List<SpeechService.Status> events = new CopyOnWriteArrayList<>();
        try (SpeechService service = new SpeechService(engine, Runnable::run)) {
            service.setListener(events::add);
            for (String text : new String[]{"", " ", "x".repeat(4001)}) {
                service.speak(text, "English", 0);
                check(events.get(events.size()-1).state() == SpeechService.State.ERROR, "Invalid text is rejected without launching engine");
            }
            service.speak("hello", "", 0); service.speak("hello", "English", 10);
            check(engine.requests.isEmpty(), "Missing voice and invalid rate do not reach engine");
            service.speak("fail", "English", 0); waitFor(() -> events.get(events.size()-1).state() == SpeechService.State.ERROR);
            check(events.get(events.size()-1).message().equals("测试语音不可用。"), "Expected backend failure gets a clear user message");
            service.speak("unexpected", "English", 0); waitFor(() -> events.get(events.size()-1).state() == SpeechService.State.ERROR);
            check(!events.get(events.size()-1).message().contains("secret"), "Unexpected backend details never leak into UI");
            service.speak("complete", "English", 0); waitFor(() -> events.get(events.size()-1).state() == SpeechService.State.READY);
            check(true, "Backend failure does not poison subsequent requests");
        }
    }

    private static void queuedCallbacks() throws Exception {
        Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>(); List<SpeechService.Status> events = new ArrayList<>();
        try (SpeechService service = new SpeechService(new FakeEngine(), callbacks::add)) {
            service.setListener(events::add); service.speak("first", "English", 0);
            waitFor(() -> callbacks.size() >= 1); service.stop();
            for (Runnable item; (item = callbacks.poll()) != null;) item.run();
            check(events.size() == 1 && events.get(0).state() == SpeechService.State.STOPPED,
                    "Already queued old callbacks cannot overwrite a newer Stop state");
            events.clear(); service.speak("complete", "English", 0);
            waitFor(() -> callbacks.size() >= 2); service.speak("", "English", 0);
            for (Runnable item; (item = callbacks.poll()) != null;) item.run();
            check(events.size() == 1 && events.get(0).state() == SpeechService.State.ERROR,
                    "Already queued old completion cannot overwrite the newest request's validation state");
        }
    }

    static final class FakeEngine implements SpeechService.Engine {
        final List<SpeechService.Request> requests = new CopyOnWriteArrayList<>();
        final Set<String> started = ConcurrentHashMap.newKeySet(), cancelled = ConcurrentHashMap.newKeySet();
        volatile boolean closed; volatile int inventories;
        @Override public List<SpeechService.Voice> voices(SpeechService.Cancellation token) { inventories++; return List.of(new SpeechService.Voice("English", "en-US")); }
        @Override public void speak(SpeechService.Request request, SpeechService.Cancellation token) throws Exception {
            requests.add(request); started.add(request.text());
            if (request.text().equals("fail")) throw new SpeechService.SpeechException("测试语音不可用。");
            if (request.text().equals("unexpected")) throw new IllegalStateException("secret diagnostics");
            if (request.text().equals("complete")) return;
            CountDownLatch stopped = new CountDownLatch(1); token.bind(stopped::countDown);
            try { stopped.await(5, TimeUnit.SECONDS); token.check(); }
            catch (InterruptedException expected) { cancelled.add(request.text()); throw expected; }
            finally { if (token.isCancelled()) cancelled.add(request.text()); token.clear(); }
        }
        @Override public void saveWave(SpeechService.Request request, Path file, SpeechService.Cancellation token) { throw new AssertionError("Unexpected WAV operation"); }
        @Override public void close() { closed = true; }
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.call()) { if (System.nanoTime() > deadline) throw new AssertionError("Timed out"); Thread.sleep(5); }
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
