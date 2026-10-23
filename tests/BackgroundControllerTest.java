package wordnest;

import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Pure policy tests. No Window, SystemTray, TrayIcon, clipboard, or user files are accessed. */
public final class BackgroundControllerTest {
    private static int assertions;

    public static void main(String[] args) {
        testDefaultsAndInvalidSettings();
        testCancelDoesNothing();
        testUnrememberedChoice();
        testRememberedTrayAndExit();
        testPreferencesPersistence();
        testUnsupportedTray();
        testTrayInstallationFailure();
        testFailedNoteSave();
        testOrdinaryMinimizeAndRestore();
        testExplicitExit();
        testSettingSaveFailure();
        testLostTrayRecovery();
        testReentrancy();
        System.out.println("PASS: " + assertions + " background lifecycle policy assertions (no desktop access)");
    }

    private static void testDefaultsAndInvalidSettings() {
        Fixture f = new Fixture();
        equal(f.controller.closeAction(), BackgroundController.CloseAction.ASK, "default X asks");
        equal(f.controller.minimizeAction(), BackgroundController.MinimizeAction.TASKBAR, "default minus uses taskbar");
        equal(f.settings.getProperty("closeAction"), "ask", "default persisted value is normalized in memory");
        equal(f.settings.getProperty("minimizeAction"), "taskbar", "default minimize setting is normalized");
        check(f.host.events.isEmpty(), "construction does not invoke desktop effects or persistence");
        Properties invalid = new Properties();
        invalid.setProperty("closeAction", "unexpected");
        invalid.setProperty("minimizeAction", "exit");
        invalid.setProperty("lastWord", "coordinate");
        Fixture broken = new Fixture(invalid);
        equal(broken.controller.closeAction(), BackgroundController.CloseAction.ASK, "invalid close setting falls back");
        equal(broken.controller.minimizeAction(), BackgroundController.MinimizeAction.TASKBAR, "invalid minimize setting falls back");
        equal(broken.settings.getProperty("lastWord"), "coordinate", "other preferences survive normalization");
        Properties existing = new Properties();
        existing.setProperty("closeAction", " TRAY ");
        existing.setProperty("minimizeAction", "TRAY");
        Fixture valid = new Fixture(existing);
        equal(valid.settings.getProperty("closeAction"), "tray", "known close preference accepts old casing");
        equal(valid.settings.getProperty("minimizeAction"), "tray", "known minimize preference accepts old casing");
    }

    private static void testCancelDoesNothing() {
        Fixture f = new Fixture();
        f.host.decision = new BackgroundController.CloseDecision(BackgroundController.CloseChoice.CANCEL, true);
        check(!f.controller.requestClose(), "cancel rejects the close request even if remember is checked");
        equal(f.host.events, List.of("ask"), "cancel does not flush, hide, exit, or persist");
        equal(f.settings.getProperty("closeAction"), "ask", "cancel cannot remember a preference");
        check(f.host.visible, "cancel keeps window visible");
        check(!f.controller.isHiddenToTray(), "cancel does not change hidden state");
        f.host.decision = null;
        check(!f.controller.requestClose(), "dismissed dialog is cancellation");
        equal(f.host.saveCount, 0, "dismissed dialog does not save settings");
    }

    private static void testUnrememberedChoice() {
        Fixture f = new Fixture();
        f.host.decision = decision(BackgroundController.CloseChoice.TRAY, false);
        check(f.controller.requestClose(), "tray choice succeeds");
        equal(f.host.events, List.of("ask", "flush", "ensure", "hide"), "note and tray are ready before hiding");
        check(f.controller.isHiddenToTray() && !f.host.visible && f.host.trayInstalled, "hidden window retains installed tray");
        equal(f.settings.getProperty("closeAction"), "ask", "one-off choice does not alter preference");
        check(!f.host.stopped && !f.host.exited, "background lookup service keeps running");
        f.controller.restoreFromTray();
        check(f.host.visible && !f.controller.isHiddenToTray(), "explicit restore shows the window");
        check(f.host.focused, "only explicit restore asks for focus");
        f.host.events.clear();
        f.host.decision = decision(BackgroundController.CloseChoice.CANCEL, false);
        check(!f.controller.requestClose(), "later close asks again without remember");
        equal(f.host.events, List.of("ask"), "later request reopens the choice");
    }

    private static void testRememberedTrayAndExit() {
        Fixture tray = new Fixture();
        tray.host.decision = decision(BackgroundController.CloseChoice.TRAY, true);
        check(tray.controller.requestClose(), "remembered tray close succeeds");
        equal(tray.host.events, List.of("ask", "flush", "ensure", "save", "hide"), "remember happens after prerequisites and before hiding");
        equal(tray.host.saved.getProperty("closeAction"), "tray", "tray preference reaches persistence callback");
        tray.controller.restoreFromTray();
        tray.host.events.clear();
        check(tray.controller.requestClose(), "remembered next close succeeds");
        check(!tray.host.events.contains("ask"), "remembered close does not ask again");
        Fixture restarted = new Fixture(tray.host.saved);
        check(restarted.controller.requestClose(), "saved choice survives a controller restart");
        check(!restarted.host.events.contains("ask"), "restart does not discard saved tray preference");

        Fixture exit = new Fixture();
        exit.host.decision = decision(BackgroundController.CloseChoice.EXIT, true);
        check(exit.controller.requestClose(), "remembered exit succeeds");
        equal(exit.host.saved.getProperty("closeAction"), "exit", "exit preference is persisted");
        equal(exit.host.events, List.of("ask", "flush", "save", "stop", "remove", "dispose", "exit"), "exit cleanup order");
        check(exit.controller.isExiting() && exit.host.exited, "successful exit is terminal");
    }

    private static void testPreferencesPersistence() {
        Fixture f = new Fixture();
        check(f.controller.setPreferences(BackgroundController.CloseAction.TRAY, BackgroundController.MinimizeAction.TRAY), "settings update succeeds");
        equal(f.host.saveCount, 1, "both settings are saved together");
        equal(f.host.saved.getProperty("closeAction"), "tray", "close preference saved");
        equal(f.host.saved.getProperty("minimizeAction"), "tray", "minimize preference saved");
        check(f.host.visible, "preference changes do not hide an active window");
        check(f.controller.setPreferences(BackgroundController.CloseAction.ASK, BackgroundController.MinimizeAction.TASKBAR), "settings can restore original behavior");
        equal(f.host.saved.getProperty("closeAction"), "ask", "ask preference restored");
        equal(f.host.saved.getProperty("minimizeAction"), "taskbar", "taskbar preference restored");
    }

    private static void testUnsupportedTray() {
        Fixture f = new Fixture();
        f.host.supported = false;
        f.host.decision = decision(BackgroundController.CloseChoice.TRAY, true);
        check(!f.controller.requestClose(), "unsupported tray cannot hide the window");
        check(!f.host.askTrayAvailable, "choice dialog is told tray is unavailable");
        check(f.host.visible && !f.controller.isHiddenToTray(), "unsupported host remains visible");
        check(!f.host.events.contains("ensure") && !f.host.events.contains("hide"), "unsupported platform never installs or hides");
        equal(f.settings.getProperty("closeAction"), "ask", "failed hide does not remember tray setting");
        check(f.host.lastMessage.contains("不支持"), "unsupported tray has a useful explanation");
        check(!f.host.focused, "failure recovery does not request focus");
        f.host.events.clear();
        f.host.decision = decision(BackgroundController.CloseChoice.EXIT, false);
        check(f.controller.requestClose(), "unsupported platform can still exit");
        check(f.host.exited, "unsupported tray does not trap user in the app");
    }

    private static void testTrayInstallationFailure() {
        for (boolean throwsError : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            f.host.installSucceeds = false;
            f.host.installThrows = throwsError;
            f.host.decision = decision(BackgroundController.CloseChoice.TRAY, true);
            check(!f.controller.requestClose(), "tray installation failure rejects hiding: " + throwsError);
            check(f.host.visible && !f.controller.isHiddenToTray(), "tray failure leaves return path visible");
            check(!f.host.events.contains("hide") && !f.host.events.contains("save"), "failed installation neither hides nor remembers");
            check(f.host.lastMessage.contains("未能添加"), "tray installation failure is explained");
        }
        Fixture f = new Fixture();
        f.controller.setPreferences(BackgroundController.CloseAction.ASK, BackgroundController.MinimizeAction.TRAY);
        f.host.events.clear();
        f.host.installSucceeds = false;
        f.host.state = Frame.MAXIMIZED_BOTH | Frame.ICONIFIED;
        f.controller.onWindowStateChanged(Frame.MAXIMIZED_BOTH, f.host.state);
        equal(f.host.state, Frame.MAXIMIZED_BOTH, "failed native minimize restores maximized state");
        check(f.host.visible && !f.host.focused, "failed native minimize recovers without focus request");
    }

    private static void testFailedNoteSave() {
        for (boolean throwsError : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            f.host.flushSucceeds = false;
            f.host.flushThrows = throwsError;
            f.host.decision = decision(BackgroundController.CloseChoice.TRAY, true);
            check(!f.controller.requestClose(), "failed note save blocks tray close: " + throwsError);
            check(f.host.visible && !f.host.trayInstalled, "failed note save leaves editor available");
            check(!f.host.events.contains("ensure") && !f.host.events.contains("save"), "note failure precedes tray setup and preferences");
            equal(f.settings.getProperty("closeAction"), "ask", "note failure cannot remember the choice");
            check(f.host.lastMessage.contains("例句"), "note failure has an explanation");
            f.host.events.clear();
            check(!f.controller.explicitExit(), "failed note save also blocks explicit exit");
            check(!f.host.stopped && !f.host.exited, "failed exit keeps watchers and app alive");
            check(!f.host.events.contains("remove") && !f.host.events.contains("dispose"), "failed save cannot destroy return route");
        }
    }

    private static void testOrdinaryMinimizeAndRestore() {
        Fixture f = new Fixture();
        f.host.state = Frame.ICONIFIED;
        f.controller.onWindowStateChanged(Frame.NORMAL, Frame.ICONIFIED);
        check(f.host.events.isEmpty(), "taskbar default leaves native minimization unchanged");
        check(!f.controller.isHiddenToTray(), "taskbar minimize is not a hidden-to-tray state");
        f.controller.setPreferences(BackgroundController.CloseAction.ASK, BackgroundController.MinimizeAction.TRAY);
        f.host.events.clear();
        f.host.state = Frame.MAXIMIZED_BOTH;
        f.controller.onWindowStateChanged(Frame.ICONIFIED, Frame.MAXIMIZED_BOTH);
        f.host.state = Frame.MAXIMIZED_BOTH | Frame.ICONIFIED;
        f.controller.onWindowStateChanged(Frame.MAXIMIZED_BOTH, f.host.state);
        equal(f.host.events, List.of("flush", "ensure", "hide"), "tray minimize uses the same prerequisites without close dialog");
        check(f.controller.isHiddenToTray(), "configured minimize hides to tray");
        f.host.events.clear();
        f.controller.onWindowStateChanged(Frame.ICONIFIED, Frame.ICONIFIED);
        check(f.host.events.isEmpty(), "repeated native state notification does not re-hide");
        f.host.callbackOnShow = () -> f.controller.onWindowStateChanged(Frame.ICONIFIED, Frame.MAXIMIZED_BOTH);
        f.controller.restoreFromTray();
        equal(f.host.state, Frame.MAXIMIZED_BOTH, "restore retains maximized state without ICONIFIED bit");
        check(f.host.visible && f.host.focused && !f.controller.isHiddenToTray(), "explicit restore completes despite nested state event");
        equal(f.host.events, List.of("show:6:true"), "restore event cannot trigger another hide");
        f.host.state = Frame.NORMAL;
        f.controller.onWindowStateChanged(Frame.MAXIMIZED_BOTH, Frame.NORMAL);
        f.host.decision = decision(BackgroundController.CloseChoice.TRAY, false);
        check(f.controller.requestClose(), "close after unmaximize still works");
        f.host.callbackOnShow = null;
        f.controller.restoreFromTray();
        equal(f.host.state, Frame.NORMAL, "later unmaximize replaces old remembered state");
    }

    private static void testExplicitExit() {
        Fixture f = new Fixture();
        f.host.decision = decision(BackgroundController.CloseChoice.TRAY, true);
        f.controller.requestClose();
        f.host.events.clear();
        check(f.controller.explicitExit(), "explicit exit works while hidden with remembered tray behavior");
        equal(f.host.events, List.of("flush", "save", "stop", "remove", "dispose", "exit"), "explicit exit bypasses close prompt and hides no further");
        equal(f.settings.getProperty("closeAction"), "tray", "explicit exit does not replace preferred X behavior");
        check(!f.controller.isHiddenToTray() && !f.host.trayInstalled, "exit removes hidden/tray state");
        check(f.host.stopped && f.host.disposed && f.host.exited, "exit completes every cleanup callback");
        f.host.events.clear();
        check(!f.controller.explicitExit() && !f.controller.requestClose(), "duplicate exit is ignored");
        f.controller.restoreFromTray();
        f.controller.recoverLostTray();
        check(f.host.events.isEmpty(), "terminal controller cannot restore or run cleanup twice");

        Fixture failed = new Fixture();
        failed.host.decision = decision(BackgroundController.CloseChoice.TRAY, false);
        failed.controller.requestClose();
        failed.host.flushSucceeds = false;
        failed.host.events.clear();
        check(!failed.controller.explicitExit(), "hidden exit with unsaved note is rejected");
        check(failed.host.visible && !failed.controller.isHiddenToTray(), "failed hidden exit restores editor");
        check(failed.host.trayInstalled && !failed.host.stopped && !failed.host.disposed, "failed hidden exit retains app resources");
        check(!failed.host.focused, "failed hidden exit recovery does not focus");
    }

    private static void testSettingSaveFailure() {
        Fixture f = new Fixture();
        f.host.saveThrows = true;
        check(!f.controller.setPreferences(BackgroundController.CloseAction.EXIT, BackgroundController.MinimizeAction.TRAY), "settings save failure is observable");
        equal(f.controller.closeAction(), BackgroundController.CloseAction.ASK, "failed settings save rolls back close choice");
        equal(f.controller.minimizeAction(), BackgroundController.MinimizeAction.TASKBAR, "failed settings save rolls back minimize choice");
        check(f.host.visible && !f.host.exited, "failed settings edit retains active window");
        f.host.events.clear();
        f.host.decision = decision(BackgroundController.CloseChoice.TRAY, true);
        check(!f.controller.requestClose(), "remember save failure blocks hiding");
        check(!f.host.events.contains("hide"), "window stays visible even when tray installation succeeded");
        equal(f.controller.closeAction(), BackgroundController.CloseAction.ASK, "failed remember does not silently change preference");
        f.host.events.clear();
        check(!f.controller.explicitExit(), "explicit exit does not silently ignore setting save failure");
        check(!f.host.stopped && !f.host.exited, "failed pre-exit save leaves services active");
        f.host.saveThrows = false;
        check(f.controller.explicitExit(), "exit can be retried after storage recovers");
    }

    private static void testLostTrayRecovery() {
        Fixture f = new Fixture();
        f.controller.recoverLostTray();
        check(f.host.events.isEmpty(), "watchdog does nothing for an already visible window");
        f.host.state = Frame.MAXIMIZED_BOTH;
        f.host.decision = decision(BackgroundController.CloseChoice.TRAY, true);
        f.controller.requestClose();
        f.host.events.clear();
        f.host.trayInstalled = false;
        f.controller.recoverLostTray();
        check(f.host.visible && !f.controller.isHiddenToTray(), "missing tray restores visible window");
        equal(f.host.state, Frame.MAXIMIZED_BOTH, "watchdog preserves maximized state");
        check(!f.host.focused, "watchdog never steals focus");
        check(f.host.lastMessage.contains("托盘图标已失效"), "lost tray recovery explains the problem");
        equal(f.settings.getProperty("closeAction"), "tray", "temporary tray loss preserves user's preference");
        f.host.events.clear();
        f.controller.recoverLostTray();
        check(f.host.events.isEmpty(), "lost-tray recovery does not repeat after restoring");
    }

    private static void testReentrancy() {
        Fixture f = new Fixture();
        f.host.onAsk = () -> check(!f.controller.requestClose(), "nested close while dialog is open is ignored");
        f.host.decision = decision(BackgroundController.CloseChoice.TRAY, false);
        check(f.controller.requestClose(), "outer close still completes");
        equal(f.host.events, List.of("ask", "flush", "ensure", "hide"), "reentrant close does not duplicate prompt or action");
    }

    private static BackgroundController.CloseDecision decision(BackgroundController.CloseChoice choice, boolean remember) {
        return new BackgroundController.CloseDecision(choice, remember);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object actual, Object expected, String message) {
        check(java.util.Objects.equals(actual, expected), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static final class Fixture {
        final Properties settings = new Properties();
        final FakeHost host = new FakeHost(settings);
        final BackgroundController controller;
        Fixture() { this(new Properties()); }
        Fixture(Properties initial) {
            settings.putAll(initial);
            controller = new BackgroundController(settings, host);
        }
    }

    private static final class FakeHost implements BackgroundController.Host {
        final Properties settings;
        final Properties saved = new Properties();
        final List<String> events = new ArrayList<>();
        BackgroundController.CloseDecision decision = decision(BackgroundController.CloseChoice.CANCEL, false);
        boolean supported = true;
        boolean installSucceeds = true;
        boolean installThrows;
        boolean flushSucceeds = true;
        boolean flushThrows;
        boolean saveThrows;
        boolean visible = true;
        boolean focused;
        boolean trayInstalled;
        boolean stopped;
        boolean disposed;
        boolean exited;
        boolean askTrayAvailable;
        int state = Frame.NORMAL;
        int saveCount;
        String lastMessage = "";
        Runnable onAsk;
        Runnable callbackOnShow;

        FakeHost(Properties settings) { this.settings = settings; }
        @Override public boolean flushNote() {
            events.add("flush");
            if (flushThrows) throw new IllegalStateException("fake failed note save");
            return flushSucceeds;
        }
        @Override public boolean traySupported() { return supported; }
        @Override public boolean ensureTray() {
            events.add("ensure");
            if (installThrows) throw new IllegalStateException("fake tray installation failure");
            trayInstalled = installSucceeds;
            return installSucceeds;
        }
        @Override public int windowState() { return state; }
        @Override public void hideWindow() { events.add("hide"); visible = false; }
        @Override public void showWindow(int restoredState, boolean focus) {
            events.add("show:" + restoredState + ":" + focus);
            state = restoredState;
            visible = true;
            focused = focus;
            if (callbackOnShow != null) callbackOnShow.run();
        }
        @Override public BackgroundController.CloseDecision askClose(boolean trayAvailable) {
            events.add("ask");
            askTrayAvailable = trayAvailable;
            if (onAsk != null) onAsk.run();
            return decision;
        }
        @Override public void saveSettings() {
            events.add("save");
            saveCount++;
            if (saveThrows) throw new IllegalStateException("fake preference save failure");
            saved.clear();
            saved.putAll(settings);
        }
        @Override public void inform(String message) { events.add("inform"); lastMessage = message; }
        @Override public void stopWatchers() { events.add("stop"); stopped = true; }
        @Override public void removeTray() { events.add("remove"); trayInstalled = false; }
        @Override public void disposeWindow() { events.add("dispose"); disposed = true; visible = false; }
        @Override public void exitApplication() { events.add("exit"); exited = true; }
    }
}
