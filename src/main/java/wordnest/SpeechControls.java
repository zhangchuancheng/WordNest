package wordnest;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/** Window-free settings row. Only an explicit play(text) request produces sound. */
final class SpeechControls extends JPanel implements AutoCloseable {
    private record Speed(int value, String label) { @Override public String toString() { return label; } }
    private final SpeechService service;
    private final JComboBox<SpeechService.Voice> voices = new JComboBox<>();
    private final JComboBox<Speed> speeds = new JComboBox<>();
    private final JButton stop = new JButton("停止朗读");
    private final JTextArea status = new JTextArea("正在读取本机英文语音…");
    private final BiConsumer<String, Integer> preferencesChanged;
    private boolean loading = true;
    private boolean closed;

    SpeechControls() { this("", 0, (voice, rate) -> { }); }
    SpeechControls(String preferredVoice, int preferredRate, BiConsumer<String, Integer> preferencesChanged) {
        this(new SpeechService(), preferredVoice, preferredRate, preferencesChanged);
    }
    SpeechControls(SpeechService service, String preferredVoice, int preferredRate, BiConsumer<String, Integer> preferencesChanged) {
        super(new BorderLayout(0, 5));
        this.service = Objects.requireNonNull(service); this.preferencesChanged = Objects.requireNonNull(preferencesChanged);
        setOpaque(false); setName("speech-controls");
        JPanel selectors = Theme.panel(new BorderLayout(8, 0));
        selectors.add(Theme.label("本机英文语音", 11, Theme.MUTED), BorderLayout.WEST);
        voices.setName("speech-voice"); voices.setMinimumSize(new Dimension(20, 28));
        voices.setPrototypeDisplayValue(new SpeechService.Voice("Microsoft English", "en-US"));
        voices.setEnabled(false); selectors.add(voices, BorderLayout.CENTER); add(selectors, BorderLayout.NORTH);
        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.add(Theme.label("语速  ", 11, Theme.MUTED));
        for (int rate = -3; rate <= 3; rate++) speeds.addItem(new Speed(rate, rate == 0 ? "标准" : rate < 0 ? "慢 " + rate : "快 +" + rate));
        speeds.setName("speech-rate"); speeds.setSelectedIndex(Math.max(-3, Math.min(3, preferredRate)) + 3);
        actions.add(speeds); actions.add(Box.createHorizontalStrut(12));
        stop.setName("speech-stop"); stop.setEnabled(false); stop.addActionListener(event -> stop()); actions.add(stop);
        JPanel center = Theme.panel(new BorderLayout(0, 5)); center.add(actions, BorderLayout.NORTH);
        status.setName("speech-status"); status.setEditable(false); status.setOpaque(false); status.setLineWrap(true); status.setWrapStyleWord(true);
        status.setFont(Theme.font(Font.PLAIN, 11)); status.setForeground(Theme.MUTED); status.setBorder(null);
        status.setRows(2); center.add(status, BorderLayout.CENTER); add(center, BorderLayout.CENTER);
        voices.addActionListener(event -> savePreferences()); speeds.addActionListener(event -> savePreferences());
        service.setListener(update -> {
            if (closed) return;
            stop.setEnabled(update.state() == SpeechService.State.SPEAKING);
            setStatus(update.message());
        });
        service.voices().whenComplete((available, failure) -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            if (failure != null) {
                setStatus(SpeechService.friendlyFailure(failure)); loading = false; return;
            }
            loadVoices(available, preferredVoice == null ? "" : preferredVoice);
        }));
    }

    private void loadVoices(List<SpeechService.Voice> available, String preferredVoice) {
        for (SpeechService.Voice voice : available) voices.addItem(voice);
        boolean found = false;
        for (SpeechService.Voice voice : available) if (voice.name().equals(preferredVoice)) {
            voices.setSelectedItem(voice); found = true; break;
        }
        voices.setEnabled(!available.isEmpty());
        setStatus(available.isEmpty() ? "未找到已安装的英文语音，当前无法朗读。"
                : !preferredVoice.isBlank() && !found ? "原选语音不可用，本次使用列表中的语音。" : "仅使用本机语音；点击单词或例句旁的朗读按钮。");
        loading = false;
    }

    private void savePreferences() {
        if (loading || closed) return;
        SpeechService.Voice voice = (SpeechService.Voice) voices.getSelectedItem();
        Speed speed = (Speed) speeds.getSelectedItem();
        if (voice != null && speed != null) preferencesChanged.accept(voice.name(), speed.value());
    }

    public void play(String text) {
        if (closed) return;
        if (loading) { setStatus("正在读取本机英文语音，请稍候。"); return; }
        SpeechService.Voice voice = (SpeechService.Voice) voices.getSelectedItem();
        if (voice == null) { setStatus("未找到可用英文语音，当前无法朗读。"); return; }
        service.speak(text, voice.name(), ((Speed) speeds.getSelectedItem()).value());
    }

    /** Other views can mirror this status and observe the bound property "speechStatus". */
    public String statusText() { return status.getText(); }

    private void setStatus(String text) {
        String previous = statusText();
        if (Objects.equals(previous, text)) return;
        status.setText(text); revalidate();
        firePropertyChange("speechStatus", previous, text);
    }

    public void stop() { if (!closed) service.stop(); }
    @Override public void close() { if (!closed) { closed = true; service.close(); stop.setEnabled(false); } }
}
