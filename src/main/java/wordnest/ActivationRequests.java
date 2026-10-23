package wordnest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Local, bounded activation requests; the caller must already own app.lock to become the receiver. */
final class ActivationRequests implements AutoCloseable {
    private static final String READY = "activation.ready";
    private static final String REQUEST = "activation.request";
    private static final String ACK = "activation.ack";
    private static final String PROTOCOL = "WordNest-activation-1\n";
    private static final int MAX_MESSAGE_BYTES = 256;

    enum Result { ACKNOWLEDGED, UNSUPPORTED, TIMED_OUT }

    private final Path directory;
    private final String session = UUID.randomUUID().toString();
    private String lastHandled;
    private boolean closed;

    private ActivationRequests(Path directory) {
        this.directory = directory;
    }

    static ActivationRequests start(Path directory) throws IOException {
        ActivationRequests receiver = new ActivationRequests(directory);
        // Only the lock owner calls start. Old files cannot activate a new process session.
        Files.deleteIfExists(directory.resolve(REQUEST));
        Files.deleteIfExists(directory.resolve(ACK));
        writeAtomically(directory.resolve(READY), receiver.readyMessage());
        return receiver;
    }

    /** Runs the supplied callback on the calling thread, once per request; App calls this on the EDT. */
    synchronized boolean poll(Runnable activate) throws IOException {
        if (closed) return false;
        String request = readSmall(directory.resolve(REQUEST));
        if (!validRequest(request, session) || request.equals(lastHandled)) return false;
        // Keep the request file in place: deleting it could erase a newer concurrent replacement.
        activate.run();
        lastHandled = request;
        writeAtomically(directory.resolve(ACK), request);
        return true;
    }

    /** A missing marker means an older owner; waiting is always limited and never uses a network port. */
    static Result requestAndAwait(Path directory, long timeoutMillis) throws IOException, InterruptedException {
        if (timeoutMillis < 0 || timeoutMillis > 5_000) {
            throw new IllegalArgumentException("Activation timeout must be between 0 and 5000 ms");
        }
        String ready = readSmall(directory.resolve(READY));
        String session = readSession(ready);
        if (session == null) return Result.UNSUPPORTED;
        String request = PROTOCOL + session + "\n" + UUID.randomUUID() + "\n";
        writeAtomically(directory.resolve(REQUEST), request);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            if (!ready.equals(readSmall(directory.resolve(READY)))) return Result.UNSUPPORTED;
            if (request.equals(readSmall(directory.resolve(ACK)))) return Result.ACKNOWLEDGED;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return Result.TIMED_OUT;
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(40)));
        } while (true);
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        // Do not remove another receiver's files, even when close is invoked late.
        if (readyMessage().equals(readSmall(directory.resolve(READY)))) {
            Files.deleteIfExists(directory.resolve(READY));
            Files.deleteIfExists(directory.resolve(REQUEST));
            Files.deleteIfExists(directory.resolve(ACK));
        }
    }

    private String readyMessage() { return PROTOCOL + session + "\n"; }

    private static String readSession(String message) {
        if (message == null || !message.startsWith(PROTOCOL)) return null;
        String rest = message.substring(PROTOCOL.length());
        if (rest.length() != 37 || rest.charAt(36) != '\n') return null;
        String value = rest.substring(0, 36);
        return isUuid(value) ? value : null;
    }

    private static boolean validRequest(String message, String session) {
        String prefix = PROTOCOL + session + "\n";
        return message != null && message.startsWith(prefix)
                && message.length() == prefix.length() + 37
                && message.endsWith("\n")
                && isUuid(message.substring(prefix.length(), message.length() - 1));
    }

    private static boolean isUuid(String text) {
        try { return UUID.fromString(text).toString().equals(text); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static String readSmall(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_MESSAGE_BYTES + 1);
            return bytes.length > MAX_MESSAGE_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".activation-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
