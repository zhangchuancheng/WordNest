package wordnest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Windows SAPI bridge: fixed packaged script, pipe-only request data, no network or downloads. */
final class WindowsSpeechEngine implements SpeechService.Engine {
    @FunctionalInterface interface ProcessStarter { Process start(List<String> command, Path output) throws IOException; }
    private final Path executable;
    private final ProcessStarter starter;
    private final long inventoryTimeoutMillis;
    private final long speechTimeoutMillis;
    private final Set<Process> processes = new HashSet<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "wordnest-speech-timeout"); thread.setDaemon(true); return thread;
    });
    private Path script;
    private boolean closed;

    WindowsSpeechEngine() {
        this(findPowerShell(), (command, output) -> new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start(), 20_000, 180_000);
    }

    WindowsSpeechEngine(Path executable, ProcessStarter starter, long inventoryTimeoutMillis, long speechTimeoutMillis) {
        this.executable = executable; this.starter = Objects.requireNonNull(starter);
        this.inventoryTimeoutMillis = inventoryTimeoutMillis; this.speechTimeoutMillis = speechTimeoutMillis;
    }

    private static Path findPowerShell() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return null;
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) return null;
        Path path = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        return Files.isRegularFile(path) ? path : null;
    }

    @Override public List<SpeechService.Voice> voices(SpeechService.Cancellation cancellation) throws Exception {
        List<String> lines = execute("voices", new SpeechService.Request("", "", 0), "", cancellation, inventoryTimeoutMillis);
        List<SpeechService.Voice> voices = new ArrayList<>();
        for (String line : lines) if (line.startsWith("VOICE\t")) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 3) throw new SpeechService.SpeechException("无法读取本机语音列表。");
            String name = decode(fields[1]); String culture = decode(fields[2]);
            if (!name.isBlank() && (culture.equalsIgnoreCase("en") || culture.toLowerCase(Locale.ROOT).startsWith("en-")))
                voices.add(new SpeechService.Voice(name, culture));
        }
        return voices.stream().distinct().toList();
    }

    @Override public void speak(SpeechService.Request request, SpeechService.Cancellation cancellation) throws Exception {
        validate(request); execute("speak", request, "", cancellation, speechTimeoutMillis);
    }

    /** Explicit diagnostics path. It never configures the default audio output. */
    @Override public void saveWave(SpeechService.Request request, Path file, SpeechService.Cancellation cancellation) throws Exception {
        validate(request); Objects.requireNonNull(file);
        execute("wave", request, file.toAbsolutePath().toString(), cancellation, speechTimeoutMillis);
    }

    private static void validate(SpeechService.Request request) throws SpeechService.SpeechException {
        if (request == null || request.text() == null || request.text().isBlank() || request.text().length() > SpeechService.MAX_TEXT_LENGTH
                || request.voice() == null || request.voice().isBlank() || request.rate() < -3 || request.rate() > 3)
            throw new SpeechService.SpeechException("朗读参数无效，请重新选择文字、语音和语速。");
    }

    private List<String> execute(String operation, SpeechService.Request request, String wavePath,
                                 SpeechService.Cancellation cancellation, long timeoutMillis) throws Exception {
        cancellation.check();
        Path output = Files.createTempFile("wordnest-speech-result-", ".txt");
        Process process = null;
        ScheduledFuture<?> deadlineTask = null;
        AtomicBoolean timedOut = new AtomicBoolean();
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            synchronized (this) {
                if (closed) throw new SpeechService.SpeechException("朗读服务已关闭。");
                if (executable == null) throw new SpeechService.SpeechException("本机未找到 Windows PowerShell，朗读不可用。");
                if (script == null) {
                    script = Files.createTempFile("wordnest-speech-", ".ps1");
                    script.toFile().deleteOnExit();
                    try (InputStream source = WindowsSpeechEngine.class.getResourceAsStream("/speech/windows-speech.ps1")) {
                        if (source == null) throw new SpeechService.SpeechException("朗读组件缺失，请重新解压完整程序包。");
                        Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                cancellation.check();
                List<String> command = List.of(executable.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                        "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-File", script.toString());
                process = starter.start(command, output); processes.add(process);
            }
            Process running = process;
            // The deadline also covers a child that never reads stdin and blocks a full pipe.
            deadlineTask = watchdog.schedule(() -> {
                if (running.isAlive()) { timedOut.set(true); running.destroyForcibly(); }
            }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            cancellation.bind(running::destroyForcibly); cancellation.check();
            try (Writer writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII)) {
                writer.write(operation + "\n" + encode(request.voice()) + "\n" + request.rate() + "\n"
                        + encode(request.text()) + "\n" + encode(wavePath) + "\n");
            }
            if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) || timedOut.get())
                throw timeoutFailure(operation);
            cancellation.check();
            if (Files.size(output) > 131_072) throw new SpeechService.SpeechException("本机语音返回了无效结果。");
            List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
            for (String line : lines) if (line.startsWith("ERROR\t")) throw new SpeechService.SpeechException(switch (line.substring(6)) {
                case "NO_ENGLISH_VOICE" -> "未找到已安装的英文语音；当前无法朗读。";
                case "VOICE_UNAVAILABLE" -> "所选语音已不可用，请选择其他本机英文语音。";
                case "INVALID_REQUEST" -> "朗读参数无效，请重新选择文字、语音和语速。";
                default -> "Windows 语音组件暂不可用，请检查本机语音设置。";
            });
            if (process.exitValue() != 0 || !lines.contains("OK"))
                throw new SpeechService.SpeechException("Windows 语音组件暂不可用，请检查本机语音设置。");
            return lines;
        } catch (IOException failure) {
            if (timedOut.get()) throw timeoutFailure(operation);
            cancellation.check(); throw failure;
        } finally {
            if (deadlineTask != null) deadlineTask.cancel(false);
            cancellation.clear();
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                synchronized (this) { processes.remove(process); }
            }
            deleteTemporary(output);
        }
    }

    private static SpeechService.SpeechException timeoutFailure(String operation) {
        return new SpeechService.SpeechException(operation.equals("voices") ? "读取本机语音超时，请稍后重试。" : "朗读超时，已停止；请缩短内容后再试。");
    }

    private static String encode(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) throws SpeechService.SpeechException {
        try { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { throw new SpeechService.SpeechException("无法读取本机语音列表。"); }
    }
    private static void deleteTemporary(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { path.toFile().deleteOnExit(); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Process process : processes) process.destroyForcibly();
        processes.clear();
        watchdog.shutdownNow();
        if (script != null) deleteTemporary(script);
    }
}
