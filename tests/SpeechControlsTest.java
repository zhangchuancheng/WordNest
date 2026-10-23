package wordnest;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Pure JPanel controls, fake voices, explicit user actions and actual narrow-width rendering. */
public final class SpeechControlsTest {
    private static int assertions;
    private static final SpeechService.Voice A = new SpeechService.Voice("English A", "en-US");
    private static final SpeechService.Voice B = new SpeechService.Voice("Microsoft English Voice With A Long Display Name", "en-GB");
    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Headless only");
        Path output = Path.of(args[0]); Files.createDirectories(output);
        SwingUtilities.invokeAndWait(Theme::install);
        check(Window.getWindows().length == 0, "No windows before UI tests");
        preferencesAndPlayback(output); unavailableAndFailure(output); statusBroadcast();
        check(Window.getWindows().length == 0, "No windows after UI tests");
        System.out.println("PASS: " + assertions + " speech controls assertions; widths 340/440/640; fake voices; no windows or audio");
    }

    private static void preferencesAndPlayback(Path output) throws Exception {
        FakeEngine engine = new FakeEngine(List.of(A, B)); List<String> saved = new ArrayList<>();
        SpeechControls[] control = new SpeechControls[1];
        SwingUtilities.invokeAndWait(() -> {
            control[0] = new SpeechControls(new SpeechService(engine), B.name(), 2, (voice, rate) -> saved.add(voice + ":" + rate));
            String initial = status(control[0]); control[0].stop();
            check(status(control[0]).equals(initial), "Stop while inventory loads does not replace its status");
            check(!voice(control[0]).isEnabled(), "Selector remains unavailable while inventory loads");
        });
        engine.inventoryReady.countDown(); waitFor(() -> onEdt(() -> voice(control[0]).isEnabled()));
        SwingUtilities.invokeAndWait(() -> {
            check(voice(control[0]).getSelectedItem().equals(B), "Saved installed voice is restored");
            check(rate(control[0]).getSelectedIndex() == 5, "Saved rate +2 is restored");
            check(saved.isEmpty(), "Programmatic inventory and saved preference restoration never write settings");
            check(engine.requests.isEmpty(), "Constructing controls never speaks automatically");
            voice(control[0]).setSelectedItem(A); rate(control[0]).setSelectedIndex(0);
            check(saved.get(saved.size()-1).equals("English A:-3"), "User voice and speed choices reach the preferences callback");
            check(engine.requests.isEmpty(), "Changing preferences does not auto-preview audio");
            control[0].play("The requested sentence stays intact.");
        });
        waitFor(() -> !engine.requests.isEmpty()); flushEdt();
        SwingUtilities.invokeAndWait(() -> {
            check(engine.requests.get(0).equals(new SpeechService.Request("The requested sentence stays intact.", A.name(), -3)), "Play submits exactly the selected voice, speed and sentence");
            check(button(control[0]).isEnabled(), "Stop becomes available during playback");
            button(control[0]).doClick();
        });
        waitFor(() -> engine.cancelled); flushEdt();
        SwingUtilities.invokeAndWait(() -> {
            check(status(control[0]).contains("已停止") && !button(control[0]).isEnabled(), "Stop cancels backend and restores idle controls");
            for (int width : new int[]{340, 440, 640}) try { render(control[0], width, output.resolve("speech-ready-" + width + ".png")); } catch (Exception e) { throw new RuntimeException(e); }
            control[0].close(); control[0].close();
            check(engine.closed, "Closing controls disposes the backend exactly once without a window");
        });
        FakeEngine missing = new FakeEngine(List.of(A)); missing.inventoryReady.countDown();
        saved.clear();
        SwingUtilities.invokeAndWait(() -> control[0] = new SpeechControls(new SpeechService(missing), "Voice no longer installed", 1,
                (voice, rate) -> saved.add(voice + rate)));
        waitFor(() -> onEdt(() -> voice(control[0]).isEnabled()));
        SwingUtilities.invokeAndWait(() -> {
            check(saved.isEmpty(), "Missing saved voice is not silently overwritten by enumeration fallback");
            check(status(control[0]).contains("原选语音不可用"), "Missing saved voice gets an explicit fallback explanation");
            control[0].stop(); check(status(control[0]).contains("原选语音不可用"), "Idle stop preserves the missing-preference explanation");
            control[0].close();
        });
    }

    private static void unavailableAndFailure(Path output) throws Exception {
        for (boolean failure : new boolean[]{false, true}) {
            FakeEngine engine = new FakeEngine(List.of()); engine.fail = failure; engine.inventoryReady.countDown();
            SpeechControls[] control = new SpeechControls[1];
            SwingUtilities.invokeAndWait(() -> control[0] = new SpeechControls(new SpeechService(engine), "", 0, (voice, rate) -> { throw new AssertionError("No inventory settings writes"); }));
            waitFor(() -> onEdt(() -> !status(control[0]).contains("正在读取")));
            SwingUtilities.invokeAndWait(() -> {
                check(!voice(control[0]).isEnabled(), "Missing or failed inventory cannot advertise a selected voice");
                String message = status(control[0]); control[0].stop();
                check(status(control[0]).equals(message), "Idle stop preserves missing or failed voice status");
                control[0].play("hello"); check(engine.requests.isEmpty(), "Unavailable voice cannot start speech");
                for (int width : new int[]{340, 440, 640}) try { render(control[0], width, output.resolve("speech-unavailable-" + failure + "-" + width + ".png")); } catch (Exception e) { throw new RuntimeException(e); }
                control[0].close();
            });
        }
    }

    private static void statusBroadcast() throws Exception {
        FakeEngine empty = new FakeEngine(List.of()); SpeechControls[] control = new SpeechControls[1];
        List<PropertyChangeEvent> updates = new ArrayList<>();
        PropertyChangeListener mirror = event -> {
            check(SwingUtilities.isEventDispatchThread(), "Speech status broadcasts run on EDT");
            check(event.getSource() == control[0] && event.getPropertyName().equals("speechStatus"), "Broadcast uses the standard named Swing property");
            check(event.getNewValue().equals(control[0].statusText()) && control[0].statusText().equals(status(control[0])),
                    "Public status and mirrored event match the visible status text");
            updates.add(event);
        };
        SwingUtilities.invokeAndWait(() -> {
            control[0] = new SpeechControls(new SpeechService(empty), "", 0, (voice, rate) -> { });
            control[0].addPropertyChangeListener("speechStatus", mirror);
            String previous = control[0].statusText(); control[0].play("hello");
            check(updates.size() == 1 && updates.get(0).getOldValue().equals(previous), "Loading message changes broadcast both previous and new status");
            control[0].play("hello"); check(updates.size() == 1, "Identical status text does not fire duplicate events");
        });
        empty.inventoryReady.countDown(); waitFor(() -> onEdt(() -> control[0].statusText().contains("未找到已安装")));
        SwingUtilities.invokeAndWait(() -> {
            check(updates.stream().anyMatch(event -> event.getNewValue().toString().contains("未找到已安装")), "A dialog mirror receives the empty-voice result");
            control[0].play("hello");
            check(updates.get(updates.size()-1).getNewValue().toString().contains("未找到可用"), "An attempted play without a voice broadcasts its explanation");
            int count = updates.size(); control[0].play("hello");
            check(updates.size() == count, "Repeated unavailable requests keep the same mirror value without duplicate events");
            control[0].removePropertyChangeListener("speechStatus", mirror); control[0].close();
        });

        FakeEngine playable = new FakeEngine(List.of(A)); playable.failSpeech = true;
        updates.clear();
        SwingUtilities.invokeAndWait(() -> {
            control[0] = new SpeechControls(new SpeechService(playable), "", 0, (voice, rate) -> { });
            control[0].addPropertyChangeListener("speechStatus", mirror);
        });
        playable.inventoryReady.countDown(); waitFor(() -> onEdt(() -> voice(control[0]).isEnabled()));
        SwingUtilities.invokeAndWait(() -> control[0].play("failure"));
        waitFor(() -> onEdt(() -> control[0].statusText().equals("测试朗读失败。")));
        SwingUtilities.invokeAndWait(() -> {
            check(updates.stream().anyMatch(event -> event.getNewValue().equals("测试朗读失败。")), "A dialog mirror receives playback failures");
            playable.failSpeech = false; control[0].play("blocking");
        });
        waitFor(() -> playable.requests.size() >= 2); flushEdt();
        SwingUtilities.invokeAndWait(() -> control[0].stop());
        waitFor(() -> onEdt(() -> control[0].statusText().contains("已停止")));
        int[] detachedCount = {0};
        SwingUtilities.invokeAndWait(() -> {
            check(updates.get(updates.size()-1).getNewValue().equals("已停止朗读。"), "A dialog mirror receives Stop status");
            control[0].removePropertyChangeListener("speechStatus", mirror);
            detachedCount[0] = updates.size(); control[0].play("");
        });
        flushEdt();
        SwingUtilities.invokeAndWait(() -> {
            check(control[0].statusText().equals("没有可朗读的文字。"), "After listener removal the underlying status still updates");
            check(updates.size() == detachedCount[0], "Removed dialog listener receives no further status events");
            control[0].close();
        });
    }

    private static void render(SpeechControls panel, int width, Path file) throws Exception {
        panel.setSize(width, panel.getPreferredSize().height); layout(panel);
        for (String name : List.of("speech-voice", "speech-rate", "speech-stop", "speech-status")) {
            Component child = named(panel, name); Rectangle bounds = SwingUtilities.convertRectangle(child.getParent(), child.getBounds(), panel);
            check(bounds.x >= 0 && bounds.getMaxX() <= width && bounds.getMaxY() <= panel.getHeight(), "Speech control remains within " + width + "px: " + name);
        }
        JTextArea status = (JTextArea) named(panel, "speech-status"); Rectangle2D last = status.modelToView2D(status.getDocument().getLength());
        check(last.getMaxY() <= status.getHeight() + 1, "Status wraps without vertical clipping at " + width);
        BufferedImage image = new BufferedImage(width * 2, panel.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.scale(2, 2); graphics.setColor(Theme.PAPER); graphics.fillRect(0, 0, width, panel.getHeight());
        panel.printAll(graphics); graphics.dispose(); ImageIO.write(image, "png", file.toFile());
    }
    private static void layout(Container panel) { panel.doLayout(); for (Component child : panel.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static Component named(Container panel, String name) {
        for (Component child : panel.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { Component found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }
    private static JComboBox<?> voice(Container panel) { return (JComboBox<?>) named(panel, "speech-voice"); }
    private static JComboBox<?> rate(Container panel) { return (JComboBox<?>) named(panel, "speech-rate"); }
    private static JButton button(Container panel) { return (JButton) named(panel, "speech-stop"); }
    private static String status(Container panel) { return ((JTextArea) named(panel, "speech-status")).getText(); }
    private static boolean onEdt(Callable<Boolean> condition) throws Exception { boolean[] result = {false}; SwingUtilities.invokeAndWait(() -> { try { result[0] = condition.call(); } catch (Exception e) { throw new RuntimeException(e); } }); return result[0]; }
    private static void flushEdt() throws Exception { SwingUtilities.invokeAndWait(() -> { }); }
    private static void waitFor(Callable<Boolean> condition) throws Exception { long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4); while (!condition.call()) { if (System.nanoTime() > deadline) throw new AssertionError("Timed out"); Thread.sleep(5); } }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }

    private static final class FakeEngine implements SpeechService.Engine {
        final CountDownLatch inventoryReady = new CountDownLatch(1); final List<SpeechService.Voice> voices;
        final List<SpeechService.Request> requests = new CopyOnWriteArrayList<>();
        volatile boolean cancelled, closed, fail, failSpeech;
        FakeEngine(List<SpeechService.Voice> voices) { this.voices = voices; }
        @Override public List<SpeechService.Voice> voices(SpeechService.Cancellation token) throws Exception {
            inventoryReady.await(4, TimeUnit.SECONDS); token.check();
            if (fail) throw new SpeechService.SpeechException("Windows 语音组件暂不可用，请检查本机语音设置。");
            return voices;
        }
        @Override public void speak(SpeechService.Request request, SpeechService.Cancellation token) throws Exception {
            requests.add(request);
            if (failSpeech) throw new SpeechService.SpeechException("测试朗读失败。");
            CountDownLatch latch = new CountDownLatch(1); token.bind(() -> { cancelled = true; latch.countDown(); });
            try { latch.await(4, TimeUnit.SECONDS); token.check(); } finally { token.clear(); }
        }
        @Override public void saveWave(SpeechService.Request request, Path file, SpeechService.Cancellation token) { throw new AssertionError("Unexpected WAV request"); }
        @Override public void close() { closed = true; }
    }
}
