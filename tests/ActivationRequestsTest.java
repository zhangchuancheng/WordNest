package wordnest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** File-protocol tests only: no App, windows, system tray, clipboard, or real user data. */
public final class ActivationRequestsTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wordnest-activation-test-");
        ExecutorService requester = Executors.newSingleThreadExecutor();
        try {
            testOlderOwner(root.resolve("old-owner"));
            testRoundTrips(root.resolve("round-trips"), requester);
            testTimeoutAndShutdown(root.resolve("timeout"), requester);
            testStaleAndMalformedRequests(root.resolve("stale"), requester);
            testWriteFailureCleanup(root.resolve("write-failure"));
            System.out.println("PASS: " + assertions + " activation file-protocol assertions (headless, temporary data only)");
        } finally {
            requester.shutdownNow();
            requester.awaitTermination(5, TimeUnit.SECONDS);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void testOlderOwner(Path directory) throws Exception {
        Files.createDirectory(directory);
        check(ActivationRequests.requestAndAwait(directory, 100) == ActivationRequests.Result.UNSUPPORTED,
                "older owner has no advertised activation support");
        check(!Files.exists(directory.resolve("activation.request")), "older owner receives no orphan request");
        Files.writeString(directory.resolve("activation.ready"), "unrelated or invalid protocol");
        check(ActivationRequests.requestAndAwait(directory, 100) == ActivationRequests.Result.UNSUPPORTED,
                "malformed support marker is not trusted");
        Files.writeString(directory.resolve("activation.ready"), "x".repeat(10_000));
        check(ActivationRequests.requestAndAwait(directory, 100) == ActivationRequests.Result.UNSUPPORTED,
                "oversized marker is ignored with bounded reads");
        expectIllegalArgument(() -> ActivationRequests.requestAndAwait(directory, -1), "negative timeout rejected");
        expectIllegalArgument(() -> ActivationRequests.requestAndAwait(directory, 5_001), "unbounded timeout rejected");
    }

    private static void testRoundTrips(Path directory, ExecutorService requester) throws Exception {
        Files.createDirectory(directory);
        AtomicInteger activations = new AtomicInteger();
        long ownerThread = Thread.currentThread().getId();
        try (ActivationRequests receiver = ActivationRequests.start(directory)) {
            check(!receiver.poll(activations::incrementAndGet), "no request means no callback");
            for (int round = 0; round < 3; round++) {
                Future<ActivationRequests.Result> result = requester.submit(() -> ActivationRequests.requestAndAwait(directory, 2_000));
                pollUntilHandled(receiver, () -> {
                    check(Thread.currentThread().getId() == ownerThread, "callback stays on receiver thread for EDT integration");
                    activations.incrementAndGet();
                });
                check(result.get(3, TimeUnit.SECONDS) == ActivationRequests.Result.ACKNOWLEDGED,
                        "request acknowledged after callback");
                check(activations.get() == round + 1, "one activation per new request");
                check(!receiver.poll(activations::incrementAndGet), "same request cannot activate repeatedly");
            }
            check(noTemporaryFiles(directory), "atomic writes leave no temporary files");
        }
        check(!Files.exists(directory.resolve("activation.ready")), "close removes capability marker");
        check(!Files.exists(directory.resolve("activation.request")), "close removes request");
        check(!Files.exists(directory.resolve("activation.ack")), "close removes acknowledgement");
    }

    private static void testTimeoutAndShutdown(Path directory, ExecutorService requester) throws Exception {
        Files.createDirectory(directory);
        ActivationRequests receiver = ActivationRequests.start(directory);
        try {
            long started = System.nanoTime();
            check(ActivationRequests.requestAndAwait(directory, 80) == ActivationRequests.Result.TIMED_OUT,
                    "unresponsive owner yields honest timeout");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000,
                    "unresponsive owner does not block indefinitely");
            check(ActivationRequests.requestAndAwait(directory, 0) == ActivationRequests.Result.TIMED_OUT,
                    "zero timeout does not wait");
            Files.delete(directory.resolve("activation.request"));
            Future<ActivationRequests.Result> result = requester.submit(() -> ActivationRequests.requestAndAwait(directory, 2_000));
            awaitRequest(directory);
            receiver.close();
            check(result.get(3, TimeUnit.SECONDS) == ActivationRequests.Result.UNSUPPORTED,
                    "owner exit ends waiting without claiming restoration");
            check(!receiver.poll(() -> { throw new AssertionError("closed receiver activated"); }),
                    "closed receiver cannot call back");
            receiver.close();
            check(!Files.exists(directory.resolve("activation.ready")), "repeated close is harmless");
        } finally {
            receiver.close();
        }
    }

    private static void testStaleAndMalformedRequests(Path directory, ExecutorService requester) throws Exception {
        Files.createDirectory(directory);
        ActivationRequests old = ActivationRequests.start(directory);
        ActivationRequests.requestAndAwait(directory, 0);
        String oldRequest = Files.readString(directory.resolve("activation.request"));
        Files.writeString(directory.resolve("activation.ack"), oldRequest);
        try (ActivationRequests current = ActivationRequests.start(directory)) {
            check(!Files.exists(directory.resolve("activation.request")), "startup clears stale request");
            check(!Files.exists(directory.resolve("activation.ack")), "startup clears stale acknowledgement");
            old.close();
            check(Files.exists(directory.resolve("activation.ready")), "late old receiver cannot clear new marker");
            Files.writeString(directory.resolve("activation.request"), oldRequest);
            check(!current.poll(() -> { throw new AssertionError("old session activated new owner"); }),
                    "prior session request cannot activate new owner");
            Files.writeString(directory.resolve("activation.request"), "x".repeat(10_000));
            check(!current.poll(() -> { throw new AssertionError("oversized message activated"); }),
                    "oversized request is rejected");
            Files.writeString(directory.resolve("activation.request"),
                    Files.readString(directory.resolve("activation.ready")) + "not-a-request-id\n");
            check(!current.poll(() -> { throw new AssertionError("malformed nonce activated"); }),
                    "invalid request identifier rejected");
            Files.delete(directory.resolve("activation.request"));
            Future<ActivationRequests.Result> result = requester.submit(() -> ActivationRequests.requestAndAwait(directory, 2_000));
            awaitRequest(directory);
            try {
                current.poll(() -> { throw new IllegalStateException("callback failed"); });
                throw new AssertionError("callback failure was suppressed");
            } catch (IllegalStateException expected) {
                check(!Files.exists(directory.resolve("activation.ack")), "failed callback is not acknowledged");
            }
            check(current.poll(() -> { }), "request can succeed after callback recovers");
            check(result.get(3, TimeUnit.SECONDS) == ActivationRequests.Result.ACKNOWLEDGED,
                    "new valid request still works after malformed files");
        } finally {
            old.close();
        }
    }

    private static void testWriteFailureCleanup(Path directory) throws Exception {
        Files.createDirectory(directory);
        try (ActivationRequests receiver = ActivationRequests.start(directory)) {
            Files.createDirectory(directory.resolve("activation.request"));
            Files.writeString(directory.resolve("activation.request/keep.txt"), "keep");
            try {
                ActivationRequests.requestAndAwait(directory, 0);
                throw new AssertionError("expected blocked request write to fail");
            } catch (IOException expected) {
                check(noTemporaryFiles(directory), "failed atomic write cleans temporary file");
                check(Files.readString(directory.resolve("activation.request/keep.txt")).equals("keep"),
                        "failed request write preserves unrelated directory contents");
            } finally {
                Files.delete(directory.resolve("activation.request/keep.txt"));
                Files.delete(directory.resolve("activation.request"));
            }
        }
    }

    private static void pollUntilHandled(ActivationRequests receiver, Runnable callback) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            if (receiver.poll(callback)) return;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("request did not arrive");
    }

    private static void awaitRequest(Path directory) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!Files.exists(directory.resolve("activation.request"))) {
            if (System.nanoTime() >= deadline) throw new AssertionError("request was not written");
            Thread.sleep(5);
        }
    }

    private static boolean noTemporaryFiles(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.noneMatch(path -> path.getFileName().toString().startsWith(".activation-"));
        }
    }

    private static void expectIllegalArgument(CheckedAction action, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException expected) { check(true, label); }
    }

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
}
