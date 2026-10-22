package wordnest;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Renders real application content controls only. This is not a screenshot of an opened window. */
public final class WordNestPreview {
    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Headless mode is required");
        Path output = Path.of(args[0]); Files.createDirectories(output);
        DictionaryRepository dictionary = DictionaryRepository.load(App.class.getResourceAsStream("/dictionary.bin.gz"));
        LearningRepository learning = LearningRepository.load();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            try {
                for (String word : new String[]{"coordinate", "relevant"}) {
                    render(build(word, dictionary, learning), 440, 570, output.resolve(word + "-content-440.png"));
                    render(build(word, dictionary, learning), 520, 705, output.resolve(word + "-content-520.png"));
                    render(build(word, dictionary, learning), 640, 900, output.resolve(word + "-content-640.png"));
                }
                if (Window.getWindows().length != 0) throw new AssertionError("A Window was unexpectedly constructed");
                System.out.println("PASS: six offscreen content previews; no native title bar simulated; no windows created");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private static JPanel build(String word, DictionaryRepository dictionary, LearningRepository learning) {
        JPanel root = new JPanel(new BorderLayout()); root.setBackground(Theme.PAPER);
        JToggleButton lookup = Theme.toggle("查词");
        JToggleButton library = Theme.toggle("生词本  2");
        JToggleButton pin = Theme.toggle("置顶"); pin.setSelected(true);
        JToggleButton watch = Theme.toggle("复制查词"); watch.setSelected(true);
        root.add(new LookupToolbar(lookup, library, pin, watch, Theme.button("说明", false)), BorderLayout.NORTH);
        JPanel page = Theme.panel(new BorderLayout(0, 12)); Theme.pad(page, 0, 18, 0, 18);
        JPanel searchRow = Theme.panel(new BorderLayout(8, 0));
        JTextField search = new JTextField(word); search.setFont(Theme.font(Font.PLAIN, 14));
        search.setForeground(Theme.INK); search.setBackground(Theme.WHITE);
        search.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Theme.LINE, 1, true), new EmptyBorder(10, 11, 10, 11)));
        searchRow.add(search, BorderLayout.CENTER); searchRow.add(Theme.button("查询", true), BorderLayout.EAST);
        page.add(searchRow, BorderLayout.NORTH);
        JPanel body = Theme.panel(null); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.add(new WordDetailPanel(dictionary.lookup(word).orElseThrow(), Theme.button("已收藏", false), null));
        body.add(Box.createVerticalStrut(12));
        Theme.Card learningCard = new Theme.Card(new BorderLayout());
        Theme.pad(learningCard, 14, 17, 16, 17); learningCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        learningCard.add(new LearningPanel(word, learning, dictionary, ignored -> { }), BorderLayout.CENTER);
        body.add(learningCard);
        JPanel details = Theme.panel(new BorderLayout()); details.add(body, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(details); scroll.setBorder(null); scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(20); page.add(scroll, BorderLayout.CENTER);
        root.add(page, BorderLayout.CENTER);
        JPanel footer = Theme.panel(new BorderLayout(0, 4)); Theme.pad(footer, 10, 19, 10, 19);
        footer.add(Theme.label("已开启复制查词 · 不抢占正在阅读的窗口", 11, Theme.MUTED), BorderLayout.NORTH);
        footer.add(Theme.label("本地离线  ·  770,611 个词条  ·  ECDICT", 11, Theme.MUTED), BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        return root;
    }

    private static void render(JPanel panel, int width, int height, Path file) throws Exception {
        panel.setSize(width, height);
        for (int pass = 0; pass < 10; pass++) { invalidate(panel); layout(panel); }
        int scale = 2;
        BufferedImage image = new BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.scale(scale, scale); g.setColor(Theme.PAPER); g.fillRect(0, 0, width, height);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        panel.printAll(g); g.dispose(); ImageIO.write(image, "png", file.toFile());
    }
    private static void invalidate(Container root) {
        root.invalidate(); for (Component c : root.getComponents()) if (c instanceof Container nested) invalidate(nested);
    }
    private static void layout(Container root) {
        root.doLayout(); for (Component c : root.getComponents()) if (c instanceof Container nested) layout(nested);
    }
}
