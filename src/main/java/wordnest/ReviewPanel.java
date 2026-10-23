package wordnest;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Pure Swing content: creates no Window, opens no dialogs, and owns no desktop services. */
public final class ReviewPanel extends JPanel {
    private final ReviewStore store;
    private final Supplier<List<SavedWord>> favorites;
    private final Runnable openLibrary;
    private final Consumer<String> speak;
    private final Runnable stopSpeech;
    private final JPanel content = new ScrollContent();
    private final JPanel questionActions = Theme.panel(new BorderLayout());
    private final JTextArea message = text("", 12, Theme.GREEN, "review-message");
    private int limit = 10;
    private boolean showingRound;
    private boolean revealed;
    private String displayedWord;

    public ReviewPanel(ReviewStore store, Supplier<List<SavedWord>> favorites, Runnable openLibrary) {
        this(store, favorites, openLibrary, null);
    }

    public ReviewPanel(ReviewStore store, Supplier<List<SavedWord>> favorites, Runnable openLibrary, Consumer<String> speak) {
        this(store, favorites, openLibrary, speak, () -> {});
    }

    public ReviewPanel(ReviewStore store, Supplier<List<SavedWord>> favorites, Runnable openLibrary,
                       Consumer<String> speak, Runnable stopSpeech) {
        super(new BorderLayout(0, 12));
        this.store = Objects.requireNonNull(store, "store");
        this.favorites = Objects.requireNonNull(favorites, "favorites");
        this.openLibrary = Objects.requireNonNull(openLibrary, "openLibrary");
        this.speak = speak;
        this.stopSpeech = Objects.requireNonNull(stopSpeech, "stopSpeech");
        setName("review-panel"); setOpaque(false); Theme.pad(this, 18, 18, 18, 18);
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel footer = Theme.panel(new BorderLayout(0, 10));
        footer.add(questionActions, BorderLayout.CENTER); footer.add(message, BorderLayout.SOUTH);
        add(scroll, BorderLayout.CENTER); add(footer, BorderLayout.SOUTH);
        setPreferredSize(new Dimension(460, 520));
        refresh();
    }

    /** Call on the EDT when entering this panel or after favorite membership changes. */
    public void refresh() {
        List<SavedWord> current = favorites.get();
        try {
            render(store.syncFavorites(current), current);
            message.setText("");
        } catch (IOException ex) {
            render(store.snapshot(current), current);
            failed();
        }
    }

    private void begin() {
        List<SavedWord> current = favorites.get();
        try {
            ReviewStore.Snapshot result = store.startRound(limit, current);
            showingRound = result.round().filter(ReviewStore.Round::active).isPresent();
            revealed = false;
            render(result, current);
            message.setText(showingRound ? "每次选择都会保存进度，可随时关闭后继续。"
                    : "目前没有到期或未学的收藏词；到下次时间再来复习。");
        } catch (IOException ex) { failed(); }
    }

    private void answer(boolean remembered) {
        if (!revealed || displayedWord == null) return;
        List<SavedWord> current = favorites.get();
        try {
            ReviewStore.Snapshot result = store.answer(displayedWord, remembered, current);
            revealed = false;
            if (result.round().filter(ReviewStore.Round::active).isEmpty()) showingRound = false;
            render(result, current);
            message.setText("进度已保存");
        } catch (IOException ex) {
            // Do not rebuild the question: retain the revealed answer and both retry actions.
            failed();
        }
    }

    private void failed() {
        message.setForeground(new Color(0xA54837));
        message.setText("复习进度保存失败，本轮没有推进。请检查数据目录后重试；已保存的进度仍保留。");
    }

    private void render(ReviewStore.Snapshot snapshot, List<SavedWord> current) {
        message.setForeground(Theme.GREEN);
        content.removeAll();
        questionActions.removeAll();
        JPanel heading = Theme.panel(new BorderLayout(0, 7));
        heading.add(Theme.label("计划复习", 22, Theme.GREEN), BorderLayout.NORTH);
        heading.add(text("到期 " + snapshot.dueCount() + " 词 · 未学 " + snapshot.newCount() + " 词\n"
                + nextTime(snapshot), 12, Theme.MUTED, "review-summary"), BorderLayout.CENTER);
        content.add(heading, BorderLayout.NORTH);

        ReviewStore.Round round = snapshot.round().orElse(null);
        String nextWord = showingRound && round != null ? round.currentWord().orElse(null) : null;
        if (displayedWord != null && !displayedWord.equalsIgnoreCase(Objects.toString(nextWord, ""))) stopSpeech.run();
        if (showingRound && round != null && round.active()) renderQuestion(round, current);
        else renderOverview(snapshot, current);
        revalidate(); repaint();
    }

    private void renderOverview(ReviewStore.Snapshot snapshot, List<SavedWord> current) {
        displayedWord = null; revealed = false;
        JPanel body = Theme.panel(new BorderLayout(0, 14));
        ReviewStore.Round round = snapshot.round().orElse(null);
        String description;
        if (round != null && round.active()) {
            description = "上次的一轮还未完成\n已完成 " + round.answered() + " 词 · 跳过 " + round.skipped()
                    + " 词 · 剩余 " + (round.words().size() - round.position()) + " 词\n继续时从下一道未完成的题开始。";
        } else if (round != null && round.completed()) {
            description = "本轮完成\n记住 " + round.remembered() + " 词 · 忘记 " + round.forgotten()
                    + " 词 · 已取消收藏而跳过 " + round.skipped() + " 词\n进度和下次复习时间已保存。";
        } else description = "每轮最多 10 或 20 词，优先到期词，再安排未学的收藏。\n先回忆，再显示释义，按实际情况选择。";
        body.add(text(description, 15, Theme.INK, "review-round-status"), BorderLayout.NORTH);
        JPanel actionsAndHelp = Theme.panel(new BorderLayout(0, 12));
        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        if (round != null && round.active()) limit = round.words().size() > 10 ? 20 : 10;
        JComboBox<String> size = new JComboBox<>(new String[]{"10 词一轮", "20 词一轮"});
        size.setName("review-size"); size.setSelectedIndex(limit == 10 ? 0 : 1);
        size.setFont(Theme.font(Font.PLAIN, 13));
        size.setEnabled(round == null || !round.active());
        size.addActionListener(event -> limit = size.getSelectedIndex() == 0 ? 10 : 20);
        actions.add(size);
        String action = round != null && round.active() ? "继续本轮" : round != null ? "开始下一轮" : "开始复习";
        JButton start = namedButton(action, "review-start", true);
        start.setEnabled((round != null && round.active()) || snapshot.eligibleCount() > 0);
        start.addActionListener(event -> begin()); actions.add(start);
        actionsAndHelp.add(actions, BorderLayout.NORTH);
        String availability = current.stream().noneMatch(SavedWord::favorite) ? "还没有收藏词，先去生词本留下想复习的词。"
                : snapshot.eligibleCount() == 0 && (round == null || !round.active())
                ? "目前没有到期或未学的收藏词。" : "关闭后保留已保存的进度，重开可继续未完成的一轮。";
        actionsAndHelp.add(text(availability + "\n\n记住：依次间隔 1 / 3 / 7 / 14 / 30 / 60 天，之后保持 60 天。\n忘记：10 分钟后再复习，并重新从 1 天开始。", 12, Theme.MUTED, "review-plan"), BorderLayout.CENTER);
        JPanel footer = Theme.panel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton library = namedButton("查看生词本", "review-library", false);
        library.addActionListener(event -> openLibrary.run()); footer.add(library);
        JButton refresh = namedButton("刷新计划", "review-refresh", false);
        refresh.addActionListener(event -> refresh()); footer.add(refresh);
        actionsAndHelp.add(footer, BorderLayout.SOUTH);
        body.add(actionsAndHelp, BorderLayout.CENTER); content.add(body, BorderLayout.CENTER);
    }

    private void renderQuestion(ReviewStore.Round round, List<SavedWord> current) {
        String word = round.currentWord().orElseThrow();
        if (!word.equalsIgnoreCase(Objects.toString(displayedWord, ""))) revealed = false;
        displayedWord = word;
        Optional<SavedWord> saved = current.stream().filter(SavedWord::favorite)
                .filter(item -> item.entry().word().equalsIgnoreCase(word)).findFirst();
        JPanel body = Theme.panel(new BorderLayout(0, 14));
        body.add(text("第 " + (round.position() + 1) + " / " + round.words().size() + " 词 · 已记住 "
                + round.remembered() + " · 忘记 " + round.forgotten() + " · 跳过 " + round.skipped(),
                12, Theme.MUTED, "review-position"), BorderLayout.NORTH);
        if (saved.isEmpty()) {
            body.add(text("当前词已取消收藏，需要先保存跳过进度。", 14, Theme.INK, "review-canceled"), BorderLayout.CENTER);
            JButton retry = namedButton("重试保存", "review-retry", true);
            retry.addActionListener(event -> refresh()); questionActions.add(retry, BorderLayout.CENTER);
        } else {
            WordEntry entry = saved.get().entry();
            JPanel card = Theme.panel(new BorderLayout(0, 10));
            JTextArea headword = text(entry.word() + (entry.phonetic().isBlank() ? "" : "\n" + entry.phonetic()),
                    25, Theme.GREEN, "review-word");
            headword.setFont(Theme.latin(Font.PLAIN, 25)); card.add(headword, BorderLayout.NORTH);
            JTextArea definition = text(revealed ? entry.translation() + (saved.get().note().isBlank() ? "" : "\n\n我的例句\n" + saved.get().note())
                    : "先回忆中文意思，再查看释义。", 15, Theme.INK, "review-definition");
            card.add(definition, BorderLayout.CENTER);
            JPanel controls = Theme.panel(new GridLayout(0, 1, 0, 8));
            if (speak != null) {
                JButton pronounce = namedButton("朗读单词", "review-speak", false);
                pronounce.addActionListener(event -> speak.accept(entry.word())); controls.add(pronounce);
            }
            JButton reveal = namedButton("显示释义", "review-reveal", false);
            reveal.setEnabled(!revealed);
            reveal.addActionListener(event -> { revealed = true; render(store.snapshot(favorites.get()), favorites.get()); });
            controls.add(reveal);
            JPanel answers = Theme.panel(new GridLayout(1, 2, 8, 0));
            JButton forgot = namedButton("忘记", "review-forgot", false), remembered = namedButton("记住", "review-remembered", true);
            forgot.setEnabled(revealed); remembered.setEnabled(revealed);
            forgot.addActionListener(event -> answer(false)); remembered.addActionListener(event -> answer(true));
            answers.add(forgot); answers.add(remembered); controls.add(answers);
            questionActions.add(controls, BorderLayout.CENTER); body.add(card, BorderLayout.CENTER);
        }
        content.add(body, BorderLayout.CENTER);
    }

    private static String nextTime(ReviewStore.Snapshot snapshot) {
        return snapshot.nextDue().map(value -> "下次计划时间：" + DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(ZoneId.systemDefault()).format(value)).orElse("尚无待安排的未来复习时间");
    }

    private static JButton namedButton(String label, String name, boolean primary) {
        JButton button = Theme.button(label, primary); button.setName(name); return button;
    }

    private static JTextArea text(String value, int size, Color color, String name) {
        JTextArea area = new JTextArea(value);
        area.setName(name); area.setEditable(false); area.setOpaque(false);
        area.setLineWrap(true); area.setWrapStyleWord(true); area.setFont(Theme.font(Font.PLAIN, size)); area.setForeground(color);
        area.setColumns(25); return area;
    }

    /** Follow the viewport width so narrow dialogs wrap instead of silently clipping buttons. */
    private static final class ScrollContent extends JPanel implements Scrollable {
        ScrollContent() { super(new BorderLayout(0, 12)); setOpaque(false); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return Math.max(16, visibleRect.height - 16); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return getParent() != null && getPreferredSize().height < getParent().getHeight(); }
    }
}
