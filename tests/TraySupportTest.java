package wordnest;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless rejection/lifecycle checks only; no native tray, clipboard, or Window is created. */
public final class TraySupportTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Run with -Djava.awt.headless=true");
        AtomicInteger callbacks = new AtomicInteger();
        TraySupport tray = new TraySupport(new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB),
            callbacks::incrementAndGet, callbacks::incrementAndGet,
            ignored -> callbacks.incrementAndGet(), callbacks::incrementAndGet);

        check(!tray.isInstalled(), "construction must not install an icon");
        check(tray.failureReason().isEmpty(), "no failure before an install attempt");
        tray.setClipboardEnabled(true);
        tray.setClipboardEnabled(false);
        check(callbacks.get() == 0, "programmatic clipboard synchronization must not notify callbacks");

        // install() rejects the environment before SystemTray.isSupported or any peer construction.
        check(!tray.install(), "headless installation must fail safely");
        check(tray.failureReason().contains("没有图形桌面"), "headless rejection explains why");
        check(!tray.install(), "repeated attempts remain safe");
        check(!tray.isInstalled(), "failed registration must not claim success");
        tray.showBackgroundHintOnce();
        tray.showBackgroundHintOnce();
        tray.close();
        tray.close();
        check(!tray.isInstalled(), "close is idempotent after failed installation");

        EventQueue.invokeAndWait(() -> {
            check(!tray.install(), "EDT invocation rejects headless safely too");
            tray.setClipboardEnabled(true);
            tray.showBackgroundHintOnce();
            tray.close();
            check(!tray.isInstalled(), "EDT lifecycle leaves no installed icon");
        });

        // A caller interruption must be preserved without leaving an unobserved queued operation.
        Thread.currentThread().interrupt();
        try {
            check(!tray.install(), "interrupted caller receives the completed failure result");
            check(Thread.currentThread().isInterrupted(), "caller interruption is preserved");
        } finally {
            Thread.interrupted();
        }
        tray.close();
        check(callbacks.get() == 0, "headless lifecycle never fires user interaction callbacks");
        check(Window.getWindows().length == 0, "verification creates no windows");
        System.out.println("PASS: " + assertions + " TraySupport headless lifecycle assertions (no native tray or windows)");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
