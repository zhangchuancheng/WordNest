package wordnest;

import java.awt.Frame;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Window lifecycle policy. All desktop effects are supplied by the host. Call on the EDT. */
final class BackgroundController {
    enum CloseAction { ASK, TRAY, EXIT }
    enum MinimizeAction { TASKBAR, TRAY }
    enum CloseChoice { TRAY, EXIT, CANCEL }

    record CloseDecision(CloseChoice choice, boolean remember) {
        CloseDecision { Objects.requireNonNull(choice); }
    }

    interface Host {
        boolean flushNote();
        boolean traySupported();
        /** Returns true only after a tray icon has actually been installed. */
        boolean ensureTray();
        int windowState();
        void hideWindow();
        /** Apply restoredState, show the window, and request focus only when focus is true. */
        void showWindow(int restoredState, boolean focus);
        CloseDecision askClose(boolean trayAvailable);
        /** May throw a RuntimeException to reject an unsuccessful preference save. */
        void saveSettings();
        void inform(String message);
        void stopWatchers();
        void removeTray();
        void disposeWindow();
        void exitApplication();
    }

    private static final String CLOSE_KEY = "closeAction";
    private static final String MINIMIZE_KEY = "minimizeAction";
    private final Properties settings;
    private final Host host;
    private int restoredState;
    private boolean hiddenToTray;
    private boolean transitioning;
    private boolean exiting;

    BackgroundController(Properties settings, Host host) {
        this.settings = Objects.requireNonNull(settings);
        this.host = Objects.requireNonNull(host);
        restoredState = withoutIconified(host.windowState());
        // Invalid or old settings are safe in memory immediately and persist on the next save.
        settings.setProperty(CLOSE_KEY, closeAction().name().toLowerCase(Locale.ROOT));
        settings.setProperty(MINIMIZE_KEY, minimizeAction().name().toLowerCase(Locale.ROOT));
    }

    CloseAction closeAction() {
        return parse(CloseAction.class, settings.getProperty(CLOSE_KEY), CloseAction.ASK);
    }

    MinimizeAction minimizeAction() {
        return parse(MinimizeAction.class, settings.getProperty(MINIMIZE_KEY), MinimizeAction.TASKBAR);
    }

    boolean isHiddenToTray() { return hiddenToTray; }
    boolean isExiting() { return exiting; }

    boolean setPreferences(CloseAction close, MinimizeAction minimize) {
        Objects.requireNonNull(close);
        Objects.requireNonNull(minimize);
        return savePreferences(close, minimize);
    }

    /** The title-bar X and Alt+F4 share this path. */
    boolean requestClose() {
        if (transitioning || exiting) return false;
        transitioning = true;
        try {
            CloseAction action = closeAction();
            boolean remember = false;
            if (action == CloseAction.ASK) {
                CloseDecision decision = host.askClose(supportsTray());
                if (decision == null || decision.choice() == CloseChoice.CANCEL) return false;
                action = decision.choice() == CloseChoice.TRAY ? CloseAction.TRAY : CloseAction.EXIT;
                remember = decision.remember();
            }
            return action == CloseAction.TRAY ? hideToTray(remember) : exit(remember);
        } finally {
            transitioning = false;
        }
    }

    /** A tray-menu Exit is explicit and must never be interpreted as another request to hide. */
    boolean explicitExit() {
        if (transitioning || exiting) return false;
        transitioning = true;
        try { return exit(false); }
        finally { transitioning = false; }
    }

    /** Feed native window-state events here, including changes made using the keyboard. */
    void onWindowStateChanged(int oldState, int newState) {
        if (transitioning || exiting || hiddenToTray) return;
        if ((newState & Frame.ICONIFIED) == 0) {
            restoredState = withoutIconified(newState);
            return;
        }
        if ((oldState & Frame.ICONIFIED) != 0) return;
        restoredState = withoutIconified(oldState);
        if (minimizeAction() == MinimizeAction.TASKBAR) return;
        transitioning = true;
        try { hideToTray(false); }
        finally { transitioning = false; }
    }

    /** Invoke only from an explicit user action, never from a clipboard lookup callback. */
    void restoreFromTray() {
        if (transitioning || exiting) return;
        transitioning = true;
        try {
            host.showWindow(restoredState, true);
            hiddenToTray = false;
        } finally {
            transitioning = false;
        }
    }

    /** Called when the host detects that its installed tray icon is no longer available. */
    void recoverLostTray() {
        if (!hiddenToTray || transitioning || exiting) return;
        transitioning = true;
        try {
            keepVisible("系统托盘图标已失效，词巢已恢复窗口。请重新选择最小化到托盘，或通过设置更改关闭方式。");
        } finally {
            transitioning = false;
        }
    }

    private boolean hideToTray(boolean remember) {
        rememberCurrentState();
        if (!flushSafely()) return false;
        if (!supportsTray()) {
            keepVisible("当前系统不支持通知区域托盘，词巢已保留在窗口中。可在设置中改为最小化到任务栏或直接退出。");
            return false;
        }
        boolean installed;
        try { installed = host.ensureTray(); }
        catch (RuntimeException error) { installed = false; }
        if (!installed) {
            keepVisible("未能添加系统托盘图标，词巢已保留在窗口中。请稍后重试，或选择直接退出。");
            return false;
        }
        if (remember && !savePreferences(CloseAction.TRAY, minimizeAction())) {
            keepVisible(null);
            return false;
        }
        // Installation is confirmed before this line, so the window always has a return route.
        host.hideWindow();
        hiddenToTray = true;
        return true;
    }

    private boolean exit(boolean remember) {
        rememberCurrentState();
        if (!flushSafely()) return false;
        if (remember) {
            if (!savePreferences(CloseAction.EXIT, minimizeAction())) {
                keepVisible(null);
                return false;
            }
        } else {
            try { host.saveSettings(); }
            catch (RuntimeException error) {
                keepVisible("设置保存失败，词巢尚未退出。请检查数据目录后重试。");
                return false;
            }
        }
        exiting = true;
        host.stopWatchers();
        host.removeTray();
        hiddenToTray = false;
        host.disposeWindow();
        host.exitApplication();
        return true;
    }

    private boolean flushSafely() {
        boolean saved;
        try { saved = host.flushNote(); }
        catch (RuntimeException error) { saved = false; }
        if (!saved) keepVisible("例句尚未保存成功，词巢已保留在窗口中。请先处理保存问题，再最小化到托盘或退出。");
        return saved;
    }

    private boolean savePreferences(CloseAction close, MinimizeAction minimize) {
        String previousClose = settings.getProperty(CLOSE_KEY);
        String previousMinimize = settings.getProperty(MINIMIZE_KEY);
        settings.setProperty(CLOSE_KEY, close.name().toLowerCase(Locale.ROOT));
        settings.setProperty(MINIMIZE_KEY, minimize.name().toLowerCase(Locale.ROOT));
        try {
            host.saveSettings();
            return true;
        } catch (RuntimeException error) {
            restoreProperty(CLOSE_KEY, previousClose);
            restoreProperty(MINIMIZE_KEY, previousMinimize);
            host.inform("设置未能保存，已保留原来的关闭和最小化方式。请检查数据目录后重试。");
            return false;
        }
    }

    private void restoreProperty(String key, String value) {
        if (value == null) settings.remove(key);
        else settings.setProperty(key, value);
    }

    private void rememberCurrentState() {
        int state = host.windowState();
        if (!hiddenToTray && (state & Frame.ICONIFIED) == 0) restoredState = withoutIconified(state);
    }

    private boolean supportsTray() {
        try { return host.traySupported(); }
        catch (RuntimeException error) { return false; }
    }

    private void keepVisible(String explanation) {
        // A failed native minimization needs deiconifying; a failed tray exit needs showing.
        host.showWindow(restoredState, false);
        hiddenToTray = false;
        if (explanation != null) host.inform(explanation);
    }

    private static int withoutIconified(int state) { return state & ~Frame.ICONIFIED; }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try { return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { return fallback; }
    }
}
