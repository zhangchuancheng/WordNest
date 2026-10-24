package wordnest;

import javax.swing.*;
import javax.imageio.ImageIO;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

final class MainWindow extends JFrame {
    private final Path dataDir;
    private final Properties settings = new Properties();
    private DictionaryRepository dictionary;
    private LookupResolver resolver;
    private LearningRepository learning = LearningRepository.empty();
    private FavoritesStore favorites;
    private ReviewStore reviewStore;
    private final SpeechControls speech;
    private LookupHistory history;
    private LookupSession lookupSession;
    private LookupNavigationBar navigation;
    private final JButton undoFavorite = Theme.button("撤销取消收藏", false);
    private final JButton retainedFavorites = Theme.button("最近取消收藏", false);
    private String lastUnfavoritedWord;
    private WordEntry current;
    private final JTextField search = field("输入英文单词或短语，按 Enter 查询");
    private final LookupCandidatesPanel candidates = new LookupCandidatesPanel(value -> lookup(value, false));
    private final JTextField librarySearch = field("搜索收藏的单词或例句");
    private final JPanel details = Theme.panel(new BorderLayout());
    private final CardLayout pages = new CardLayout();
    private final JPanel pagePanel = Theme.panel(pages);
    private final JToggleButton pin = Theme.toggle("置顶");
    private final JToggleButton watch = Theme.toggle("复制查词");
    private final JToggleButton lookupTab = Theme.toggle("查词");
    private final JToggleButton libraryTab = Theme.toggle("生词本  0");
    private final JLabel footer = Theme.label("正在打开离线词库…", 11, Theme.MUTED);
    private final JLabel message = Theme.label("准备就绪后，复制英文单词或短语试试看。", 11, Theme.MUTED);
    private final DefaultListModel<SavedWord> libraryModel = new DefaultListModel<>();
    private final JList<SavedWord> library = new JList<>(libraryModel);
    private final JLabel libraryCount = Theme.label("还没有收藏", 12, Theme.MUTED);
    private final ClipboardWatcher watcher;
    private final javax.swing.Timer saveNoteTimer;
    private final TraySupport tray;
    private final BackgroundController background;
    private final javax.swing.Timer trayMonitor;
    private JTextArea noteArea;
    private JLabel noteState;
    private JButton favoriteButton;
    private boolean noteDirty;
    private boolean updatingNote;
    private boolean modalOpen;
    private boolean loaded;

    MainWindow(Path dataDir) {
        super("词巢");
        this.dataDir = dataDir;
        readSettings();
        int preferredRate;
        try { preferredRate = Integer.parseInt(settings.getProperty("speech.rate", "0")); }
        catch (NumberFormatException invalid) { preferredRate = 0; }
        speech = new SpeechControls(settings.getProperty("speech.voice", ""), preferredRate, (voice, rate) -> {
            settings.setProperty("speech.voice", voice);
            settings.setProperty("speech.rate", Integer.toString(rate));
            writeSettings();
        });
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setIconImages(List.of(icon(16), icon(32), icon(64)));
        Rectangle available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(520, available.width), Math.min(740, available.height - 20));
        setMinimumSize(new Dimension(Math.min(440, available.width), Math.min(570, available.height - 20)));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.PAPER);
        setLayout(new BorderLayout());
        add(header(), BorderLayout.NORTH);
        pagePanel.add(lookupPage(), "lookup");
        pagePanel.add(libraryPage(), "library");
        add(pagePanel, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
        watcher = new ClipboardWatcher(word -> {
            if (loaded && !modalOpen && (noteArea == null || !noteArea.isFocusOwner())) lookup(word, true);
        });
        saveNoteTimer = new javax.swing.Timer(750, e -> flushNote());
        saveNoteTimer.setRepeats(false);
        pin.setSelected(Boolean.parseBoolean(settings.getProperty("pin", "true")));
        if (Toolkit.getDefaultToolkit().isAlwaysOnTopSupported()) setAlwaysOnTop(pin.isSelected());
        else { pin.setSelected(false); pin.setEnabled(false); }
        watch.setSelected(Boolean.parseBoolean(settings.getProperty("clipboard", "true")));
        watch.setToolTipText("复制英文单词或短语查询，最多 8 词 / 120 字符；整段或多行内容不触发查询");
        tray = new TraySupport(icon(32), this::restoreFromTray, this::exitFromTray,
                this::setClipboardEnabled, this::openPreferencesFromTray);
        tray.setClipboardEnabled(watch.isSelected());
        background = new BackgroundController(settings, new BackgroundController.Host() {
            @Override public boolean flushNote() { return MainWindow.this.flushNote(); }
            @Override public boolean traySupported() {
                try { return SystemTray.isSupported(); }
                catch (RuntimeException | AWTError error) { return false; }
            }
            @Override public boolean ensureTray() { return tray.install(); }
            @Override public int windowState() { return getExtendedState(); }
            @Override public void hideWindow() { speech.stop(); setVisible(false); }
            @Override public void showWindow(int state, boolean focus) {
                setAutoRequestFocus(focus);
                try {
                    setExtendedState(state);
                    if (!isVisible()) setVisible(true);
                    if (focus) { toFront(); requestFocus(); }
                } finally { setAutoRequestFocus(true); }
            }
            @Override public BackgroundController.CloseDecision askClose(boolean available) { return askCloseAction(available); }
            @Override public void saveSettings() { writeSettingsStrict(); }
            @Override public void inform(String text) { status(text, true); }
            @Override public void stopWatchers() { stopBackgroundWork(); }
            @Override public void removeTray() { tray.close(); }
            @Override public void disposeWindow() { dispose(); }
            @Override public void exitApplication() { System.exit(0); }
        });
        trayMonitor = new javax.swing.Timer(2000, e -> {
            if (!background.isHiddenToTray()) return;
            boolean trayAvailable;
            try { trayAvailable = tray.isInstalled(); }
            catch (RuntimeException | AWTError error) { trayAvailable = false; }
            if (!trayAvailable) background.recoverLostTray();
        });
        pin.addActionListener(e -> { setAlwaysOnTop(pin.isSelected()); writeSettings(); });
        watch.addActionListener(e -> setClipboardEnabled(watch.isSelected()));
        lookupTab.addActionListener(e -> pages.show(pagePanel, "lookup"));
        libraryTab.addActionListener(e -> { if (flushNote()) { speech.stop(); refreshLibrary(); pages.show(pagePanel, "library"); } else lookupTab.setSelected(true); });
        search.addActionListener(e -> lookup(search.getText(), false));
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                if (!modalOpen) { background.requestClose(); showTrayHint(); }
            }
            @Override public void windowOpened(WindowEvent e) {
                tray.install();
                trayMonitor.start();
            }
            @Override public void windowClosed(WindowEvent e) {
                stopBackgroundWork();
                tray.close();
            }
        });
        addWindowStateListener(e -> {
            background.onWindowStateChanged(e.getOldState(), e.getNewState());
            showTrayHint();
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control L"), "search");
        getRootPane().getActionMap().put("search", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { pages.show(pagePanel, "lookup"); lookupTab.setSelected(true); search.requestFocusInWindow(); search.selectAll(); }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("alt LEFT"), "history-back");
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("alt RIGHT"), "history-forward");
        getRootPane().getActionMap().put("history-back", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { navigateHistory(false); }
        });
        getRootPane().getActionMap().put("history-forward", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { navigateHistory(true); }
        });
        showEmpty("让阅读继续。", "正在准备本地词库，首次打开请稍候。", null);
        loadData();
    }

    private JPanel header() {
        JButton help = Theme.button("设置", false); help.setFont(Theme.font(Font.PLAIN, 11));
        help.addActionListener(e -> showPreferences());
        return new LookupToolbar(lookupTab, libraryTab, pin, watch, help);
    }

    /** Explicit user restoration from the tray or a second EXE launch. */
    void restoreFromTray() { background.restoreFromTray(); }

    private void exitFromTray() { if (!modalOpen) background.explicitExit(); }

    private void openPreferencesFromTray() {
        if (modalOpen) return;
        restoreFromTray();
        showPreferences();
    }

    private void stopBackgroundWork() {
        watcher.stop(); saveNoteTimer.stop(); trayMonitor.stop();
        speech.close();
    }

    private void setClipboardEnabled(boolean enabled) {
        watch.setSelected(enabled);
        watcher.setEnabled(loaded && enabled);
        tray.setClipboardEnabled(enabled);
        if (writeSettings()) status(enabled ? "复制查词已开启 · 支持英文单词和短语" : "复制查词已暂停", false);
    }

    private void showTrayHint() {
        if (!background.isHiddenToTray()) return;
        try { tray.showBackgroundHintOnce(); }
        catch (RuntimeException | AWTError ignored) { /* Notifications are optional. */ }
    }

    private BackgroundController.CloseDecision askCloseAction(boolean trayAvailable) {
        JPanel content = Theme.panel(new BorderLayout(0, 14));
        JTextArea explanation = Theme.text(trayAvailable
                ? "最小化到系统托盘后，词巢会继续在后台运行。\n双击右下角的词巢图标，或再次打开 EXE，即可显示窗口。\n若未看到图标，请展开任务栏右下角的小箭头。"
                : "当前系统托盘不可用。可以退出词巢，或取消并保留窗口。", 13, Theme.INK, 370);
        content.add(explanation, BorderLayout.CENTER);
        JCheckBox remember = new JCheckBox("记住我的选择（可在设置中更改）");
        remember.setOpaque(false); content.add(remember, BorderLayout.SOUTH);
        Object[] options = trayAvailable
                ? new Object[]{"最小化到系统托盘", "退出程序", "取消"}
                : new Object[]{"退出程序", "取消"};
        boolean previousModal = modalOpen; modalOpen = true;
        try {
            int choice = JOptionPane.showOptionDialog(this, content, "关闭词巢时…",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            BackgroundController.CloseChoice action = choice < 0 ? BackgroundController.CloseChoice.CANCEL
                    : trayAvailable ? switch (choice) {
                        case 0 -> BackgroundController.CloseChoice.TRAY;
                        case 1 -> BackgroundController.CloseChoice.EXIT;
                        default -> BackgroundController.CloseChoice.CANCEL;
                    } : choice == 0 ? BackgroundController.CloseChoice.EXIT : BackgroundController.CloseChoice.CANCEL;
            return new BackgroundController.CloseDecision(action, remember.isSelected());
        } finally { modalOpen = previousModal; }
    }

    private void showPreferences() {
        if (modalOpen) return;
        JDialog dialog = new JDialog(this, "窗口与后台设置", true);
        PreferencesPanel content = new PreferencesPanel(background.closeAction().ordinal(),
                background.minimizeAction().ordinal());
        content.save.addActionListener(e -> {
            if (background.setPreferences(BackgroundController.CloseAction.values()[content.close.getSelectedIndex()],
                    BackgroundController.MinimizeAction.values()[content.minimize.getSelectedIndex()])) {
                status("已保存窗口与后台设置", false);
                dialog.dispose();
            } else JOptionPane.showMessageDialog(dialog, "设置暂时无法保存，请检查数据目录的写入权限后重试。",
                    "设置未保存", JOptionPane.WARNING_MESSAGE);
        });
        content.cancel.addActionListener(e -> dialog.dispose());
        content.about.addActionListener(e -> showAbout());
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(content.save);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.setResizable(true);
        dialog.pack();
        Rectangle available = getGraphicsConfiguration().getBounds();
        Insets screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(getGraphicsConfiguration());
        available = new Rectangle(available.x + screenInsets.left, available.y + screenInsets.top,
                available.width - screenInsets.left - screenInsets.right,
                available.height - screenInsets.top - screenInsets.bottom);
        dialog.setSize(Math.min(dialog.getWidth(), available.width), Math.min(dialog.getHeight(), available.height));
        dialog.setMinimumSize(new Dimension(Math.min(340, available.width), Math.min(220, available.height)));
        dialog.setLocationRelativeTo(this);
        dialog.setLocation(TrayPopup.position(dialog.getX(), dialog.getY(), dialog.getSize(), available));
        modalOpen = true;
        try { dialog.setVisible(true); }
        finally { modalOpen = false; dialog.dispose(); }
    }

    private JPanel lookupPage() {
        JPanel p = Theme.panel(new BorderLayout(0, 12)); Theme.pad(p, 0, 18, 0, 18);
        JPanel searchRow = Theme.panel(new BorderLayout(8, 0));
        searchRow.add(search, BorderLayout.CENTER);
        JButton go = Theme.button("查询", true); go.addActionListener(e -> lookup(search.getText(), false));
        searchRow.add(go, BorderLayout.EAST);
        JPanel lookupControls = Theme.panel(new BorderLayout(0, 7));
        lookupControls.add(searchRow, BorderLayout.NORTH);
        lookupControls.add(candidates, BorderLayout.CENTER);
        navigation = new LookupNavigationBar(() -> navigateHistory(false), () -> navigateHistory(true), this::showHistory);
        JPanel navigationAndSpeech = Theme.panel(new BorderLayout(0, 5));
        navigationAndSpeech.add(navigation, BorderLayout.NORTH);
        navigationAndSpeech.add(speech, BorderLayout.SOUTH);
        lookupControls.add(navigationAndSpeech, BorderLayout.SOUTH); p.add(lookupControls, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(details);
        scroll.setBorder(null); scroll.setOpaque(false); scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(20); p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    private JPanel libraryPage() {
        JPanel p = Theme.panel(new BorderLayout(0, 12)); Theme.pad(p, 0, 18, 0, 18);
        p.add(librarySearch, BorderLayout.NORTH);
        library.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        library.setBackground(Theme.PAPER); library.setFixedCellHeight(77);
        library.setCellRenderer((list, item, index, selected, focused) -> {
            JPanel row = new JPanel(new BorderLayout(0, 5));
            row.setBackground(selected ? Theme.PALE : Theme.WHITE); Theme.pad(row, 10, 14, 10, 14);
            JLabel word = Theme.label(item.entry().word(), 18, Theme.INK); word.setFont(Theme.latin(Font.BOLD, 18));
            row.add(word, BorderLayout.NORTH);
            String preview = item.note().isBlank() ? item.entry().translation() : item.note();
            preview = preview.replace('\n', ' ');
            if (preview.length() > 48) preview = preview.substring(0, 48) + "…";
            row.add(Theme.label(preview, 12, Theme.MUTED), BorderLayout.SOUTH);
            row.setBorder(BorderFactory.createCompoundBorder(new MatteBorder(0, 0, 5, 0, Theme.PAPER), new EmptyBorder(10, 14, 10, 14)));
            return row;
        });
        JScrollPane scroll = new JScrollPane(library); scroll.setBorder(null); scroll.getViewport().setBackground(Theme.PAPER);
        p.add(scroll, BorderLayout.CENTER);
        JPanel bottom = Theme.panel(new BorderLayout(0, 8)); bottom.add(libraryCount, BorderLayout.NORTH);
        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton open = Theme.button("查看词条", true); open.addActionListener(e -> openSelected());
        JButton review = Theme.button("计划复习", false); review.addActionListener(e -> review());
        JButton export = Theme.button("导出 CSV", false); export.addActionListener(e -> export());
        actions.add(open); actions.add(review); actions.add(export);
        JPanel libraryActions = Theme.panel(new BorderLayout(0, 3));
        libraryActions.add(actions, BorderLayout.NORTH);
        JPanel recoveryRow = Theme.panel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        retainedFavorites.addActionListener(e -> showRetainedFavorites());
        retainedFavorites.setEnabled(false); recoveryRow.add(retainedFavorites);
        libraryActions.add(recoveryRow, BorderLayout.SOUTH);
        bottom.add(libraryActions, BorderLayout.SOUTH); p.add(bottom, BorderLayout.SOUTH);
        onChange(librarySearch, this::refreshLibrary);
        library.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) openSelected(); } });
        library.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open");
        library.getActionMap().put("open", new AbstractAction() { @Override public void actionPerformed(ActionEvent e) { openSelected(); } });
        return p;
    }

    private JPanel footer() {
        JPanel p = Theme.panel(new BorderLayout(0, 4)); Theme.pad(p, 10, 19, 10, 19);
        JPanel statusRow = Theme.panel(new BorderLayout(6, 0));
        statusRow.add(message, BorderLayout.CENTER);
        undoFavorite.setFont(Theme.font(Font.PLAIN, 11));
        undoFavorite.setBorder(new EmptyBorder(3, 7, 3, 7));
        undoFavorite.setVisible(false);
        undoFavorite.addActionListener(e -> restoreFavorite(lastUnfavoritedWord));
        statusRow.add(undoFavorite, BorderLayout.EAST); p.add(statusRow, BorderLayout.NORTH);
        p.add(footer, BorderLayout.SOUTH);
        return p;
    }

    private void loadData() {
        new SwingWorker<DictionaryRepository, Void>() {
            private FavoritesStore loadedFavorites;
            private ReviewStore loadedReview;
            private LearningRepository loadedLearning = LearningRepository.empty();
            private boolean learningFailed;
            private LookupHistory loadedHistory;
            private boolean historyFailed;
            private LookupResolver loadedResolver;
            private boolean resolverFailed;
            @Override protected DictionaryRepository doInBackground() throws Exception {
                loadedFavorites = new FavoritesStore(dataDir);
                try { loadedReview = new ReviewStore(dataDir); }
                catch (IOException e) { /* Preserve damaged review data; dictionary and notes remain available. */ }
                try { loadedHistory = new LookupHistory(dataDir); }
                catch (IOException e) { loadedHistory = LookupHistory.sessionOnly(); historyFailed = true; }
                DictionaryRepository loadedDictionary = DictionaryRepository.loadBundled(dataDir.resolve("dictionary-cache"));
                try { loadedResolver = LookupResolver.loadBundled(loadedDictionary); }
                catch (IOException e) { loadedResolver = LookupResolver.withoutInflections(loadedDictionary); resolverFailed = true; }
                try { loadedLearning = LearningRepository.load(); }
                catch (IOException e) { learningFailed = true; }
                return loadedDictionary;
            }
            @Override protected void done() {
                try {
                    dictionary = get(); favorites = loadedFavorites; learning = loadedLearning; loaded = true;
                    resolver = loadedResolver;
                    reviewStore = loadedReview;
                    history = loadedHistory;
                    lookupSession = new LookupSession(history, dictionary::lookup, MainWindow.this::flushNote, MainWindow.this::displayWord);
                    footer.setText("本地离线  ·  " + NumberFormat.getIntegerInstance().format(dictionary.size()) + " 个词条  ·  ECDICT");
                    refreshLibrary();
                    lookup(settings.getProperty("lastWord", "relevant"), false, false);
                    watcher.setEnabled(watch.isSelected());
                    status(watch.isSelected() ? "已开启复制单词 / 短语查词 · 不抢占窗口" : "输入单词或短语查询，或开启复制查词", false);
                    if (resolverFailed) status("词形资料未能加载，单词和短语的精确查询仍可使用", true);
                    if (learningFailed) status("学习资料加载失败，基础查词和生词本仍可使用", true);
                    if (historyFailed) status("历史记录未能读取，原文件已保留；本次查询历史仅保存在内存", true);
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    showEmpty("暂时无法打开词库", "本地数据已保留。\n" + cause.getMessage(), null);
                    footer.setText("加载失败 · 请查看「设置 → 关于与说明」中的数据位置");
                    status("未修改已有收藏文件", true);
                }
            }
        }.execute();
    }

    private void lookup(String text, boolean automatic) {
        lookup(text, automatic, true);
    }

    private void lookup(String text, boolean automatic, boolean remember) {
        if (!loaded) { status("词库正在加载，请稍候", false); return; }
        LookupResolver.Result result = resolver.resolve(text);
        String query = result.normalized();
        if (result.kind() == LookupResolver.MatchKind.INVALID) {
            if (!automatic) status("请输入一个单词或简短词组", true);
            return;
        }
        if (result.kind() == LookupResolver.MatchKind.AMBIGUOUS) {
            candidates.showCandidates(result);
            pages.show(pagePanel, "lookup"); lookupTab.setSelected(true);
            status("找到多个词形候选，请选择；当前词条与笔记保持不变", false);
            return;
        }
        if (!result.hasSingleMatch()) {
            if (automatic) { status(result.truncated() ? "词形组合较多，请缩短查询；已保留当前词条"
                    : "未收录「" + query + "」，已保留当前词条", false); return; }
            if (!flushNote()) return;
            candidates.clear();
            current = null; noteArea = null; noteState = null; favoriteButton = null;
            speech.stop();
            List<String> suggestions = dictionary.suggest(query, 5);
            showEmpty("没有找到这个词或短语", result.truncated() ? "词形组合较多，请缩短查询或直接输入原形。"
                    : "原词、规范化拼写和已收录词形均未命中。\n可检查拼写或缩短短语；不会自动补写释义。", suggestions);
            updateNavigation();
            status("离线词库并不包含所有词汇", false); return;
        }
        WordEntry entry = result.candidates().get(0);
        if (automatic && current != null && entry.word().equals(current.word())) {
            if (result.kind() == LookupResolver.MatchKind.INFLECTION) status("按原形「" + entry.word() + "」查询", false);
            return;
        }
        LookupSession.Result outcome = lookupSession.visit(entry, remember);
        if (!outcome.opened()) return;
        status(outcome.warning().isEmpty()
                ? result.kind() == LookupResolver.MatchKind.INFLECTION ? "按原形「" + current.word() + "」查询"
                    : result.kind() == LookupResolver.MatchKind.NORMALIZED ? "已规范化查询 · " + current.word()
                    : automatic ? "已查询复制的单词 / 短语 · " + current.word() : "查询完成 · " + current.word()
                : outcome.warning(), !outcome.warning().isEmpty());
    }

    private void displayWord(WordEntry entry) {
        speech.stop();
        candidates.clear();
        current = entry;
        search.setText(current.word());
        settings.setProperty("lastWord", current.word());
        renderWord(); pages.show(pagePanel, "lookup"); lookupTab.setSelected(true);
        updateNavigation();
    }

    private void renderWord() {
        JPanel body = Theme.panel(null); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        favoriteButton = Theme.button(favorites.contains(current.word()) ? "已收藏" : "收藏", false);
        favoriteButton.setToolTipText("取消收藏会保留个人例句，可撤销或稍后恢复"); favoriteButton.addActionListener(e -> toggleFavorite());
        JButton base = null;
        String lemma = lemma(current.exchange());
        if (lemma != null && !lemma.equalsIgnoreCase(current.word()) && dictionary.lookup(lemma).isPresent()) {
            base = Theme.button("查看原形  " + lemma, false); base.addActionListener(e -> lookup(lemma, false));
        }
        WordDetailPanel card = new WordDetailPanel(current, favoriteButton, base, speech::play);
        body.add(card); body.add(Box.createVerticalStrut(12));
        Theme.Card learningCard = new Theme.Card(new BorderLayout());
        Theme.pad(learningCard, 14, 17, 16, 17); learningCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        learningCard.add(new LearningPanel(current.word(), learning, dictionary, value -> lookup(value, false), speech::play), BorderLayout.CENTER);
        body.add(learningCard); body.add(Box.createVerticalStrut(12));
        Theme.Card notes = new Theme.Card(new BorderLayout(0, 10)); Theme.pad(notes, 15, 17, 15, 17); notes.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel noteHeading = Theme.panel(new BorderLayout());
        noteHeading.add(Theme.label("我的例句", 13, Theme.INK), BorderLayout.WEST);
        noteState = Theme.label(noteStatus(), 10, Theme.MUTED); noteHeading.add(noteState, BorderLayout.EAST);
        notes.add(noteHeading, BorderLayout.NORTH);
        noteArea = new JTextArea(3, 24); noteArea.setLineWrap(true); noteArea.setWrapStyleWord(true);
        noteArea.setFont(Theme.font(Font.PLAIN, 13)); noteArea.setForeground(Theme.INK); noteArea.setBackground(new Color(0xF5F7F3));
        noteArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        noteArea.setToolTipText("记录例句或原文语境，输入自动保存。取消收藏后仍保留笔记，继续编辑不会重新收藏。");
        updatingNote = true;
        noteArea.setText(favorites.get(current.word()).map(SavedWord::note).orElse(""));
        updatingNote = false; noteDirty = false;
        noteArea.getDocument().addDocumentListener(listener(() -> {
            if (!updatingNote) { noteDirty = true; noteState.setText("正在保存…"); saveNoteTimer.restart(); }
        }));
        notes.add(noteArea, BorderLayout.CENTER);
        JPanel noteActions = Theme.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JButton readNote = Theme.button("朗读我的例句", false);
        readNote.setFont(Theme.font(Font.PLAIN, 11));
        readNote.addActionListener(event -> speech.play(noteArea.getText()));
        noteActions.add(readNote); notes.add(noteActions, BorderLayout.SOUTH);
        body.add(notes); body.add(Box.createVerticalStrut(4));
        details.removeAll(); details.add(body, BorderLayout.NORTH); details.revalidate(); details.repaint();
        SwingUtilities.invokeLater(() -> { var scroll = (JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class, details); if (scroll != null) scroll.getVerticalScrollBar().setValue(0); });
    }

    private void showEmpty(String title, String description, List<String> suggestions) {
        Theme.Card card = new Theme.Card(null); card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS)); Theme.pad(card, 35, 24, 35, 24);
        JLabel heading = Theme.label(title, 23, Theme.INK); heading.setAlignmentX(Component.LEFT_ALIGNMENT); card.add(heading);
        card.add(Box.createVerticalStrut(18)); card.add(Theme.text(description, 14, Theme.MUTED, 340));
        if (suggestions != null && !suggestions.isEmpty()) {
            card.add(Box.createVerticalStrut(22)); card.add(Theme.label("也许你在找", 12, Theme.MUTED));
            for (String suggestion : suggestions) { card.add(Box.createVerticalStrut(6)); JButton b = Theme.button(suggestion, false); b.setAlignmentX(Component.LEFT_ALIGNMENT); b.addActionListener(e -> lookup(suggestion, false)); card.add(b); }
        }
        details.removeAll(); details.add(card, BorderLayout.NORTH); details.revalidate(); details.repaint();
    }

    private boolean flushNote() {
        if (saveNoteTimer != null) saveNoteTimer.stop();
        if (!noteDirty || current == null || noteArea == null || favorites == null) return true;
        String note = noteArea.getText();
        if (note.length() > 20000) { status("例句过长，请控制在 20,000 字符内", true); return false; }
        try {
            if (!note.isBlank() || favorites.get(current.word()).isPresent()) favorites.save(current, note);
            noteDirty = false; if (noteState != null) noteState.setText(noteStatus());
            if (favoriteButton != null) favoriteButton.setText(favorites.contains(current.word()) ? "已收藏" : "收藏");
            refreshLibrary(); return true;
        } catch (IOException e) { status("保存失败，内容仍保留在窗口中", true); if (noteState != null) noteState.setText("保存失败"); return false; }
    }

    private void toggleFavorite() {
        if (current == null || favorites == null) return;
        boolean wasFavorite = favorites.contains(current.word());
        if (!flushNote()) return;
        try {
            if (wasFavorite) {
                favorites.remove(current.word());
                lastUnfavoritedWord = current.word(); undoFavorite.setVisible(true);
                status("已取消收藏，个人例句仍保留", false);
            } else {
                favorites.setFavorite(current, noteArea == null ? "" : noteArea.getText(), true);
                if (current.word().equalsIgnoreCase(Objects.toString(lastUnfavoritedWord, ""))) {
                    lastUnfavoritedWord = null; undoFavorite.setVisible(false);
                }
                status("已加入生词本", false);
            }
            noteState.setText(noteStatus());
            favoriteButton.setText(favorites.contains(current.word()) ? "已收藏" : "收藏"); refreshLibrary();
        } catch (IOException e) { status("收藏保存失败：" + e.getMessage(), true); }
    }

    private void refreshLibrary() {
        if (favorites == null) return;
        String filter = librarySearch.getText().strip().toLowerCase(Locale.ROOT);
        libraryModel.clear(); List<SavedWord> all = favorites.list();
        for (SavedWord item : all) {
            if (filter.isEmpty() || item.entry().word().toLowerCase(Locale.ROOT).contains(filter) || item.entry().translation().contains(filter) || item.note().toLowerCase(Locale.ROOT).contains(filter)) libraryModel.addElement(item);
        }
        libraryTab.setText("生词本  " + all.size());
        libraryCount.setText(all.isEmpty() ? "查词时点「收藏」，在这里留住新词。" : "共 " + all.size() + " 个收藏 · 当前显示 " + libraryModel.size() + " 个");
        int retained = favorites.listUnfavorited().size();
        retainedFavorites.setText(retained == 0 ? "最近取消收藏" : "最近取消收藏 · " + retained);
        retainedFavorites.setEnabled(retained > 0);
    }

    private String noteStatus() {
        Optional<SavedWord> saved = favorites.get(current.word());
        return saved.isEmpty() ? "填写后自动收藏并保存"
                : saved.get().favorite() ? "已保存到生词本" : "笔记已保留 · 未收藏";
    }

    private void restoreFavorite(String word) {
        if (word == null || favorites == null || !flushNote()) return;
        try {
            favorites.restoreFavorite(word);
            if (word.equalsIgnoreCase(Objects.toString(lastUnfavoritedWord, ""))) {
                lastUnfavoritedWord = null; undoFavorite.setVisible(false);
            }
            if (current != null && current.word().equalsIgnoreCase(word)) {
                favoriteButton.setText(favorites.contains(word) ? "已收藏" : "收藏");
                noteState.setText(noteStatus());
            }
            refreshLibrary(); status("已恢复收藏 · " + word, false);
        } catch (IOException e) { status("恢复失败，保留的例句未被删除，请检查数据目录", true); }
    }

    private void showRetainedFavorites() {
        if (modalOpen || favorites == null || !flushNote()) return;
        List<SavedWord> retained = favorites.listUnfavorited();
        if (retained.isEmpty()) return;
        JList<SavedWord> list = new JList<>(retained.toArray(SavedWord[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setSelectedIndex(0);
        list.setCellRenderer((items, item, index, selected, focused) -> {
            String preview = item.note().replace('\n', ' ').replace('\r', ' ');
            if (preview.length() > 70) preview = preview.substring(0, 70) + "…";
            JLabel row = new JLabel(item.entry().word() + "  —  "
                    + (preview.isBlank() ? "没有个人例句" : preview));
            row.putClientProperty("html.disable", true); row.setOpaque(true);
            row.setFont(Theme.font(Font.PLAIN, 13)); row.setBorder(new EmptyBorder(9, 10, 9, 10));
            row.setBackground(selected ? Theme.PALE : Theme.WHITE); row.setForeground(Theme.INK);
            return row;
        });
        JScrollPane scroll = new JScrollPane(list); scroll.setPreferredSize(new Dimension(360, 260));
        JPanel content = Theme.panel(new BorderLayout(0, 10));
        content.add(Theme.text("取消收藏的词和个人例句仍在本机。\n可以恢复收藏，也可以打开词条继续编辑。", 12, Theme.MUTED, 350), BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        modalOpen = true;
        int choice;
        try {
            choice = JOptionPane.showOptionDialog(this, content, "最近取消收藏",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                    new Object[]{"恢复收藏", "查看词条", "关闭"}, "恢复收藏");
        } finally { modalOpen = false; }
        SavedWord selected = list.getSelectedValue();
        if (selected == null) return;
        if (choice == 0) restoreFavorite(selected.entry().word());
        else if (choice == 1) lookup(selected.entry().word(), false);
    }

    private void updateNavigation() {
        if (navigation != null && history != null)
            navigation.update(current == null ? history.current().isPresent() : history.canBack(),
                    current != null && history.canForward(), history.recent().size());
    }

    private void navigateHistory(boolean forward) {
        if (!loaded || modalOpen || lookupSession == null) return;
        if (forward && current == null) return;
        LookupSession.Result result = forward ? lookupSession.forward()
                : current == null ? lookupSession.redisplayCurrent() : lookupSession.back();
        updateNavigation();
        if (!result.warning().isEmpty()) status(result.warning(), true);
        else if (result.opened()) status((forward ? "已前进 · " : "已返回 · ") + current.word(), false);
    }

    private void showHistory() {
        if (modalOpen || history == null) return;
        JList<String> list = new JList<>(history.recent().toArray(String[]::new));
        list.setFont(Theme.font(Font.PLAIN, 14)); list.setFixedCellHeight(30);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setSelectedIndex(0);
        list.setSelectionBackground(Theme.PALE); list.setSelectionForeground(Theme.INK);
        JScrollPane scroll = new JScrollPane(list); scroll.setPreferredSize(new Dimension(330, 280));
        JPanel content = Theme.panel(new BorderLayout(0, 9));
        JPanel options = Theme.panel(new BorderLayout(0, 6));
        options.add(Theme.label("超出上限自动删除最早记录，仅保存在本机", 12, Theme.MUTED), BorderLayout.NORTH);
        JComboBox<Integer> capacity = new JComboBox<>(new Integer[]{50, 100});
        capacity.setSelectedItem(history.limit());
        JPanel capacityRow = Theme.panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        capacityRow.add(Theme.label("最多保留（条）：", 12, Theme.MUTED));
        capacityRow.add(capacity);
        options.add(capacityRow, BorderLayout.CENTER);
        content.add(options, BorderLayout.NORTH);
        capacity.addActionListener(e -> {
            int requested = (Integer) capacity.getSelectedItem();
            try {
                history.setLimit(requested);
                list.setListData(history.recent().toArray(String[]::new));
                if (!history.recent().isEmpty()) list.setSelectedIndex(0);
                updateNavigation();
                status("查词历史最多保留 " + requested + " 条，超出时自动清理", false);
            } catch (IOException failure) {
                capacity.setSelectedItem(history.limit());
                status("历史上限未能保存，原设置和记录已保留", true);
            }
        });
        content.add(scroll, BorderLayout.CENTER);
        modalOpen = true;
        int choice;
        try {
            choice = JOptionPane.showOptionDialog(this, content, "查词历史",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                    new Object[]{"查看词条", "清空历史", "关闭"}, "查看词条");
            if (choice == 1 && JOptionPane.showConfirmDialog(this,
                    "清空查询历史？收藏和个人例句不受影响。", "清空历史", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                try {
                    history.clear();
                    if (current != null) history.seedSession(current.word());
                    updateNavigation(); status("已清空查词历史", false);
                } catch (IOException e) { status("历史记录未能清空，原记录已保留", true); }
            }
        } finally { modalOpen = false; }
        if (choice == 0 && list.getSelectedValue() != null) lookup(list.getSelectedValue(), false);
    }

    private void openSelected() {
        SavedWord selected = library.getSelectedValue();
        if (selected == null) { status("先选一个想查看的单词", false); return; }
        lookup(selected.entry().word(), false);
    }

    private void review() {
        if (modalOpen || favorites == null) return;
        if (reviewStore == null) { status("复习记录未能读取，原文件已保留；查词与生词本仍可使用", true); return; }
        if (!flushNote()) return;
        speech.stop();
        JDialog dialog = new JDialog(this, "词巢 · 计划复习", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        ReviewPanel content = new ReviewPanel(reviewStore, favorites::list, () -> {
            dialog.dispose(); refreshLibrary(); pages.show(pagePanel, "library"); libraryTab.setSelected(true);
        }, speech::play, speech::stop);
        Rectangle available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(500, available.width), Math.min(620, available.height - 20));
        dialog.setMinimumSize(new Dimension(Math.min(380, available.width), Math.min(480, available.height - 20)));
        JPanel reviewContent = Theme.panel(new BorderLayout());
        reviewContent.add(content, BorderLayout.CENTER);
        JPanel audioActions = Theme.panel(new BorderLayout(8, 0)); Theme.pad(audioActions, 5, 18, 12, 18);
        JTextArea speechStatus = new JTextArea(speech.statusText(), 2, 20);
        speechStatus.setName("review-speech-status"); speechStatus.setEditable(false); speechStatus.setOpaque(false);
        speechStatus.setLineWrap(true); speechStatus.setWrapStyleWord(true);
        speechStatus.setFont(Theme.font(Font.PLAIN, 11)); speechStatus.setForeground(Theme.MUTED);
        java.beans.PropertyChangeListener speechUpdates = event -> speechStatus.setText((String) event.getNewValue());
        speech.addPropertyChangeListener("speechStatus", speechUpdates);
        audioActions.add(speechStatus, BorderLayout.CENTER);
        JButton stopReading = Theme.button("停止朗读", false);
        stopReading.addActionListener(event -> speech.stop()); audioActions.add(stopReading, BorderLayout.EAST);
        reviewContent.add(audioActions, BorderLayout.SOUTH);
        dialog.setLocationRelativeTo(this); dialog.setContentPane(reviewContent); modalOpen = true;
        try { content.refresh(); dialog.setVisible(true); }
        finally { speech.removePropertyChangeListener("speechStatus", speechUpdates); speech.stop(); modalOpen = false; dialog.dispose(); }
    }

    private void export() {
        if (favorites == null || favorites.list().isEmpty()) { status("生词本还是空的，先收藏一个词吧", false); return; }
        if (!flushNote()) return;
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("导出生词本"); chooser.setSelectedFile(new File("词巢-生词本.csv"));
        modalOpen = true;
        try {
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            Path target = chooser.getSelectedFile().toPath();
            if (Files.exists(target) && JOptionPane.showConfirmDialog(this, "文件已存在，要覆盖它吗？", "导出", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                writer.write("\uFEFF单词,音标,中文释义,英文释义,我的例句,收藏时间\r\n");
                for (SavedWord item : favorites.list()) {
                    String time = Instant.ofEpochMilli(item.savedAt()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    writer.write(String.join(",", csv(item.entry().word()), csv(item.entry().phonetic()), csv(item.entry().translation()), csv(item.entry().definition()), csv(item.note()), csv(time)) + "\r\n");
                }
            }
            status("已导出生词本：" + target.getFileName(), false);
        } catch (IOException e) { status("导出失败：" + e.getMessage(), true); }
        finally { modalOpen = false; }
    }

    static String csv(String value) {
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    private void showAbout() {
        String info = "词巢 0.7.5\n\n" +
                "手动输入单词或短语，或在其他软件中复制英文内容（最多 8 词 / 120 字符）。\n支持连字符、撇号与首尾标点；多行、网址、邮箱、中文和代码样式内容不触发复制查询。\n先查原词，再整理空白与标点；未命中时仅使用 ECDICT 明确记录的词形。\n唯一原形会标明「按原形查询」，多个原形请从候选条手动选择。\n复制查词不会抢焦点，可随时用顶部按钮暂停；未命中保留当前词条。\n\n" +
                "关闭时可选择后台运行或退出；顶部「设置」可更改记住的选择。\n托盘图标可能收在右下角的小箭头中，双击即可显示窗口。\n再次打开 EXE 也可唤回窗口；右键托盘菜单可彻底退出。\n\n" +
                "释义来自 ECDICT 离线词库（MIT）。\n部分词条缺音标或英文释义；词形、释义可能有疏漏。\n个人例句自动保存在本机，取消收藏仍保留，可从生词本恢复。\n查词历史可选保留最近 50 或 100 个词，可清空；所有查询在本机完成，不调用网络或 AI 服务。\n\n" +
                "英汉例句：Tatoeba 社区语料与词巢原创。\n已核对的例句会标出目标词在本句中的词性与译法；尚未核对的句子不自动猜测。\n派生和近义词：Open English WordNet / 中文维基词典。\n词源：中文维基词典与 etymology-db（2023 快照）。\n新增资料优先覆盖常用词与雅思标签词；355 张原有完整中文卡片继续保留。\n各项实际覆盖见程序目录 LEARNING_COVERAGE.md。\n每个分页标明来源；社区资料可能有疏漏，缺项不会自动补写。\n\n" +
                "计划复习：每轮 10 / 20 词，显示释义后选择忘记或记住；答题进度与下次复习时间自动保存在本机。\n朗读使用 Windows 已安装的英文桌面语音，支持语音选择、调速与停止。\n\n" +
                "本地收藏、笔记、历史、复习与设置：\n" + dataDir + "\n\n快捷键：Ctrl+L 聚焦搜索，Enter 查询，Alt+← / → 后退 / 前进。";
        boolean previousModal = modalOpen; modalOpen = true;
        try { JOptionPane.showMessageDialog(this, info, "关于词巢", JOptionPane.INFORMATION_MESSAGE, new ImageIcon(icon())); }
        finally { modalOpen = previousModal; }
    }

    private void readSettings() {
        Path path = dataDir.resolve("settings.properties");
        if (Files.isRegularFile(path)) try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { settings.load(reader); } catch (Exception ignored) { }
    }
    private boolean writeSettings() {
        try { writeSettingsStrict(); return true; }
        catch (UncheckedIOException e) { status("设置暂时无法保存", true); return false; }
    }
    private void writeSettingsStrict() {
        settings.setProperty("pin", Boolean.toString(pin.isSelected())); settings.setProperty("clipboard", Boolean.toString(watch.isSelected()));
        Path temporary = dataDir.resolve("settings.properties.tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { settings.store(writer, "WordNest local preferences"); }
            try { Files.move(temporary, dataDir.resolve("settings.properties"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, dataDir.resolve("settings.properties"), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    private void status(String text, boolean error) {
        message.setText(text); message.setForeground(error ? new Color(0xA3583D) : Theme.MUTED); message.setToolTipText(text);
    }
    static String lemma(String exchange) {
        for (String item : exchange.split("/")) if (item.startsWith("0:")) return item.substring(2).split(",")[0];
        return null;
    }
    private static JTextField field(String placeholder) {
        JTextField field = new JTextField() {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                if (getText().isEmpty() && !isFocusOwner()) { graphics.setColor(Theme.MUTED); graphics.setFont(Theme.font(Font.PLAIN, 12)); graphics.drawString(placeholder, 12, (getHeight() + graphics.getFontMetrics().getAscent() - graphics.getFontMetrics().getDescent()) / 2); }
            }
        };
        field.setFont(Theme.font(Font.PLAIN, 14)); field.setForeground(Theme.INK); field.setBackground(Theme.WHITE);
        field.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Theme.LINE, 1, true), new EmptyBorder(10, 11, 10, 11)));
        field.setPreferredSize(new Dimension(220, 43)); field.getAccessibleContext().setAccessibleName(placeholder); return field;
    }
    private static DocumentListener listener(Runnable action) {
        return new DocumentListener() { public void insertUpdate(DocumentEvent e) { action.run(); } public void removeUpdate(DocumentEvent e) { action.run(); } public void changedUpdate(DocumentEvent e) { action.run(); } };
    }
    private static void onChange(JTextField field, Runnable action) { field.getDocument().addDocumentListener(listener(action)); }
    private static final BufferedImage ICON_SOURCE = loadIcon();

    private static BufferedImage loadIcon() {
        try (InputStream input = MainWindow.class.getResourceAsStream("/app.png")) {
            if (input == null) throw new IllegalStateException("词巢图标资源缺失");
            BufferedImage image = ImageIO.read(input);
            if (image == null) throw new IllegalStateException("词巢图标无法读取");
            return image;
        } catch (IOException failure) {
            throw new IllegalStateException("词巢图标无法读取", failure);
        }
    }

    static BufferedImage icon() { return icon(64); }

    static BufferedImage icon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(ICON_SOURCE, 0, 0, size, size, null);
        } finally { g.dispose(); }
        return image;
    }
}
