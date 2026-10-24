package wordnest;

import java.awt.AWTError;
import java.awt.AWTException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Native tray adapter. Construction does not access the desktop or create AWT peers. */
final class TraySupport implements AutoCloseable {
    private final Image image;
    private final Runnable showWindow;
    private final Runnable exitApplication;
    private final Consumer<Boolean> clipboardChanged;
    private final Runnable openSettings;
    // Mutable tray state is confined to the AWT event dispatch thread.
    private SystemTray systemTray;
    private TrayIcon trayIcon;
    private TrayPopup popup;
    private boolean clipboardEnabled;
    private boolean backgroundHintShown;
    private volatile String failureReason = "";

    TraySupport(Image image, Runnable showWindow, Runnable exitApplication,
                Consumer<Boolean> clipboardChanged, Runnable openSettings) {
        this.image = Objects.requireNonNull(image, "image");
        this.showWindow = Objects.requireNonNull(showWindow, "showWindow");
        this.exitApplication = Objects.requireNonNull(exitApplication, "exitApplication");
        this.clipboardChanged = Objects.requireNonNull(clipboardChanged, "clipboardChanged");
        this.openSettings = Objects.requireNonNull(openSettings, "openSettings");
    }

    /** Returns true only after this icon has been registered; repeated calls do not add duplicates. */
    boolean install() {
        return onEdt(this::installOnEdt);
    }

    private boolean installOnEdt() {
        if (isInstalledOnEdt()) return true;
        closeOnEdt();
        if (GraphicsEnvironment.isHeadless()) {
            failureReason = "当前运行环境没有图形桌面，无法使用系统托盘。";
            return false;
        }
        SystemTray candidateTray = null;
        TrayIcon candidateIcon = null;
        try {
            if (!SystemTray.isSupported()) {
                failureReason = "当前系统不支持通知区域托盘。";
                return false;
            }
            candidateTray = SystemTray.getSystemTray();
            candidateIcon = new TrayIcon(image, tooltip());
            candidateIcon.setImageAutoSize(true);
            TrayIcon eventSource = candidateIcon;
            // Windows maps the native default action to double-click and keyboard activation.
            // Using only the ActionListener avoids dispatching the same double-click twice.
            candidateIcon.addActionListener(e -> dispatch(eventSource, showWindow));
            TrayPopup candidatePopup = new TrayPopup(
                () -> dispatch(eventSource, showWindow),
                enabled -> dispatch(eventSource, () -> {
                    clipboardEnabled = enabled;
                    trayIcon.setToolTip(tooltip());
                    clipboardChanged.accept(enabled);
                }),
                () -> dispatch(eventSource, openSettings),
                () -> dispatch(eventSource, exitApplication));
            candidateIcon.addMouseListener(new MouseAdapter() {
                @Override public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3) {
                        dispatch(eventSource, () -> candidatePopup.show(e.getX(), e.getY(), clipboardEnabled));
                    }
                }
            });
            // Do not attach an AWT PopupMenu: its native Windows text renderer can
            // show missing-glyph boxes even when Java2D's font coverage checks pass.
            candidateTray.add(candidateIcon);
            systemTray = candidateTray;
            trayIcon = candidateIcon;
            popup = candidatePopup;
            failureReason = "";
            return true;
        } catch (SecurityException e) {
            failureReason = "系统拒绝了托盘访问，请使用任务栏最小化。";
        } catch (AWTException | UnsupportedOperationException | AWTError e) {
            failureReason = "系统托盘暂时不可用，请使用任务栏最小化。";
        }
        // A failed registration must never leave an orphaned icon or a false success state.
        removeSafely(candidateTray, candidateIcon);
        return false;
    }

    /** Synchronizes the menu without notifying clipboardChanged. */
    void setClipboardEnabled(boolean enabled) {
        onEdt(() -> {
            clipboardEnabled = enabled;
            if (popup != null) popup.setClipboardEnabled(enabled);
            if (trayIcon != null) trayIcon.setToolTip(tooltip());
            return null;
        });
    }

    boolean isInstalled() {
        return onEdt(this::isInstalledOnEdt);
    }

    private boolean isInstalledOnEdt() {
        if (systemTray == null || trayIcon == null) return false;
        for (TrayIcon registered : systemTray.getTrayIcons()) {
            if (registered == trayIcon) return true;
        }
        // The desktop may remove icons if its notification area becomes unavailable.
        failureReason = "系统托盘已不可用，请使用任务栏最小化。";
        return false;
    }

    String failureReason() {
        return failureReason;
    }

    /** Optional one-time hint, to be called only after the window has actually been hidden. */
    void showBackgroundHintOnce() {
        onEdt(() -> {
            if (!backgroundHintShown && isInstalledOnEdt()) {
                backgroundHintShown = true;
                trayIcon.displayMessage("词巢仍在后台运行",
                    "双击托盘图标恢复窗口；右键菜单可修改设置或退出词巢。", TrayIcon.MessageType.NONE);
            }
            return null;
        });
    }

    @Override public void close() {
        onEdt(() -> { closeOnEdt(); return null; });
    }

    private void closeOnEdt() {
        SystemTray oldTray = systemTray;
        TrayIcon oldIcon = trayIcon;
        systemTray = null;
        trayIcon = null;
        if (popup != null) popup.close();
        popup = null;
        removeSafely(oldTray, oldIcon);
    }

    private static void removeSafely(SystemTray tray, TrayIcon icon) {
        if (tray == null || icon == null) return;
        try {
            tray.remove(icon);
        } catch (SecurityException | UnsupportedOperationException | AWTError ignored) {
            // Native removal can race with desktop shutdown; the JVM also removes icons on exit.
        }
    }

    private String tooltip() {
        return "词巢 — 双击显示窗口 · 复制查词" + (clipboardEnabled ? "已开启" : "已暂停");
    }

    private void dispatch(TrayIcon eventSource, Runnable action) {
        Runnable guarded = () -> {
            if (trayIcon == eventSource && isInstalledOnEdt()) action.run();
        };
        if (EventQueue.isDispatchThread()) guarded.run();
        else EventQueue.invokeLater(guarded);
    }

    private static <T> T onEdt(Supplier<T> action) {
        if (EventQueue.isDispatchThread()) return action.get();
        FutureTask<T> task = new FutureTask<>(action::get);
        EventQueue.invokeLater(task);
        boolean interrupted = false;
        try {
            for (;;) {
                try {
                    return task.get();
                } catch (InterruptedException e) {
                    // Complete the queued operation before returning its result to the caller.
                    interrupted = true;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    if (cause instanceof Error error) throw error;
                    throw new IllegalStateException(cause);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
