package wordnest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

/** Fake process exercises the exact command/data boundary, output parsing, timeout and teardown. */
public final class WindowsSpeechEngineTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        safeProtocol(); failures(); cancellation(); blockedInputDeadline();
        System.out.println("PASS: " + assertions + " Windows speech bridge assertions; fake processes; no audio");
    }

    private static void safeProtocol() throws Exception {
        List<List<String>> commands = new ArrayList<>(); List<FakeProcess> processes = new ArrayList<>();
        WindowsSpeechEngine.ProcessStarter starter = (command, output) -> {
            commands.add(command); FakeProcess process = new FakeProcess(true); processes.add(process);
            Files.writeString(output, "VOICE\t" + b64("English ' Voice") + "\t" + b64("en-US")
                    + "\nVOICE\t" + b64("Chinese") + "\t" + b64("zh-CN") + "\nOK\n"); return process;
        };
        try (WindowsSpeechEngine engine = new WindowsSpeechEngine(Path.of("fake powershell.exe"), starter, 30, 30)) {
            var voices = engine.voices(new SpeechService.Cancellation());
            check(voices.equals(List.of(new SpeechService.Voice("English ' Voice", "en-US"))), "Only installed English voice records are exposed");
            String payload = "Hello 'quoted' \"double\"; $(Write-Output injected) `whoami`\n中文 <speak>literal</speak>";
            engine.speak(new SpeechService.Request(payload, "English ' Voice", -3), new SpeechService.Cancellation());
            List<String> command = commands.get(1);
            check(command.containsAll(List.of("-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-File")), "PowerShell is noninteractive and explicitly hidden");
            check(command.stream().noneMatch(s -> s.contains(payload) || s.contains("English ' Voice") || s.contains("Write-Output")),
                    "No text, selected voice, shell punctuation or user command enters the executable command line");
            String[] fields = processes.get(1).input.toString(StandardCharsets.US_ASCII).split("\n", -1);
            check(fields[0].equals("speak") && fields[2].equals("-3"), "Operation and bounded rate are separate data fields");
            check(decode(fields[3]).equals(payload) && decode(fields[1]).equals("English ' Voice"), "Quoted and non-ASCII input round-trips solely through stdin data");
            Path wave = Path.of(System.getProperty("java.io.tmpdir"), "a 'quoted' wave.wav");
            engine.saveWave(new SpeechService.Request("Hello", "English ' Voice", 0), wave, new SpeechService.Cancellation());
            fields = processes.get(2).input.toString(StandardCharsets.US_ASCII).split("\n", -1);
            check(fields[0].equals("wave") && decode(fields[4]).equals(wave.toAbsolutePath().toString()), "WAV target also travels only as data");
            check(commands.get(2).stream().noneMatch(s -> s.contains(wave.toString())), "WAV path cannot become executable syntax");
            Path script = Path.of(command.get(command.size()-1));
            check(Files.readString(script).contains("$synth.Speak($text)") && !Files.readString(script).contains("Invoke-Expression"), "Fixed bridge speaks literal text, without evaluating data");
        }
        check(commands.stream().allMatch(c -> !Files.exists(Path.of(c.get(c.size()-1)))), "Engine close removes its extracted script");
    }

    private static void failures() throws Exception {
        for (String response : List.of("ERROR\tNO_ENGLISH_VOICE\n", "ERROR\tVOICE_UNAVAILABLE\n", "unstructured private error\n")) {
            try (WindowsSpeechEngine engine = new WindowsSpeechEngine(Path.of("fake.exe"), (command, output) -> {
                Files.writeString(output, response); return new FakeProcess(true);
            }, 1, 1)) {
                try { engine.speak(new SpeechService.Request("Hello", "English", 0), new SpeechService.Cancellation()); throw new AssertionError("Expected failure"); }
                catch (SpeechService.SpeechException failure) { check(!failure.getMessage().contains("private"), "Failures receive a friendly bounded message"); }
            }
        }
        FakeProcess timeout = new FakeProcess(false);
        try (WindowsSpeechEngine engine = new WindowsSpeechEngine(Path.of("fake.exe"), (command, output) -> timeout, 1, 1)) {
            try { engine.voices(new SpeechService.Cancellation()); throw new AssertionError("Expected timeout"); }
            catch (SpeechService.SpeechException failure) { check(failure.getMessage().contains("超时"), "Timeout is distinguishable from missing voice"); }
            check(timeout.destroyed, "Timed-out process is terminated");
        }
    }

    private static void cancellation() throws Exception {
        SpeechService.Cancellation token = new SpeechService.Cancellation(); token.cancel();
        int[] starts = {0};
        try (WindowsSpeechEngine engine = new WindowsSpeechEngine(Path.of("fake.exe"), (command, output) -> { starts[0]++; return new FakeProcess(true); }, 1, 1)) {
            try { engine.voices(token); throw new AssertionError("Expected cancellation"); }
            catch (InterruptedException expected) { check(starts[0] == 0, "Cancelled requests do not create processes"); }
        }
        SpeechService.Cancellation duringStart = new SpeechService.Cancellation(); FakeProcess process = new FakeProcess(false);
        try (WindowsSpeechEngine engine = new WindowsSpeechEngine(Path.of("fake.exe"), (command, output) -> { duringStart.cancel(); return process; }, 1, 1)) {
            try { engine.voices(duringStart); throw new AssertionError("Expected cancellation"); }
            catch (InterruptedException expected) { check(process.destroyed, "Cancellation during process creation kills the new process"); }
        }
    }

    private static void blockedInputDeadline() throws Exception {
        CountDownLatch terminated = new CountDownLatch(1);
        FakeProcess blocked = new FakeProcess(false) {
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int value) throws IOException {
                        try {
                            if (!terminated.await(2, TimeUnit.SECONDS)) throw new AssertionError("Watchdog failed to terminate blocked stdin writer");
                        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                        throw new IOException("Pipe closed when child terminated");
                    }
                };
            }
            @Override public Process destroyForcibly() { super.destroyForcibly(); terminated.countDown(); return this; }
        };
        try (WindowsSpeechEngine engine = new WindowsSpeechEngine(Path.of("fake.exe"), (command, output) -> blocked, 50, 50)) {
            long start = System.nanoTime();
            try { engine.speak(new SpeechService.Request("x".repeat(4000), "English", 0), new SpeechService.Cancellation()); throw new AssertionError("Expected blocked-input timeout"); }
            catch (SpeechService.SpeechException failure) {
                check(failure.getMessage().contains("超时"), "A child not reading stdin still produces the friendly timeout status");
            }
            check(blocked.destroyed && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500,
                    "Overall deadline terminates a blocked stdin write before waitFor is reached");
        }
    }

    static class FakeProcess extends Process {
        final ByteArrayOutputStream input = new ByteArrayOutputStream(); final boolean completes; boolean destroyed, finished;
        FakeProcess(boolean completes) { this.completes = completes; }
        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { finished = true; return 0; }
        @Override public boolean waitFor(long time, TimeUnit unit) { finished = completes; return completes; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
        @Override public boolean isAlive() { return !destroyed && !finished; }
    }
    private static String b64(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String text) { return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
