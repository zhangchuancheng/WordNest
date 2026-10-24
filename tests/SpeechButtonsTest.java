package wordnest;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Tests the actual reading controls with a recording callback; never creates a voice engine. */
public final class SpeechButtonsTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Headless mode required");
        Path output = Path.of(args[0]); Files.createDirectories(output);
        DictionaryRepository dictionary = DictionaryTestResources.load();
        LearningRepository learning = LearningRepository.load();
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            try {
                List<String> spoken = new ArrayList<>();
                for (String word : List.of("relevant", "certificate", "internationalization")) {
                    JPanel detail = new WordDetailPanel(dictionary.lookup(word).orElseThrow(), Theme.button("已收藏", false), null, spoken::add);
                    JButton playWord = buttons(detail, "speak-word").get(0);
                    playWord.doClick();
                    check(spoken.get(spoken.size() - 1).equals(word), "Word playback uses exact headword");
                    for (int width : new int[]{340, 440, 640}) render(detail, width, null);
                    LearningPanel panel = new LearningPanel(word, learning, dictionary, ignored -> { }, spoken::add);
                    List<JButton> examples = buttons(panel, "speak-example");
                    for (JButton button : examples) {
                        button.doClick();
                        String actual = spoken.get(spoken.size() - 1);
                        check(actual.equals(button.getAccessibleContext().getAccessibleDescription()), "Reads complete visible English sentence");
                        check(!actual.isBlank(), "Does not send an empty example");
                    }
                    if (word.equals("certificate")) {
                        check(spoken.get(spoken.size() - 1).equals("Where's my birth certificate?"), "Apostrophe and question mark retained");
                    }
                    for (int width : new int[]{340, 440, 640}) render(panel, width, output.resolve(word + "-speech-" + width + ".png"));
                }
                check(Window.getWindows().length == 0, "No windows were created");
                System.out.println("PASS: " + checks + " speech button callback/layout assertions; no voice engine or sound");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
    private static List<JButton> buttons(Container parent, String name) {
        List<JButton> result = new ArrayList<>();
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton button && name.equals(button.getName())) result.add(button);
            if (child instanceof Container nested) result.addAll(buttons(nested, name));
        }
        return result;
    }
    private static void render(JPanel panel, int width, Path file) throws Exception {
        panel.setSize(width, 20000);
        for (int i = 0; i < 10; i++) { invalidate(panel); layout(panel); panel.setSize(width, panel.getPreferredSize().height); }
        layout(panel);
        for (String name : List.of("speak-word", "speak-example")) for (JButton button : buttons(panel, name)) {
            Rectangle bounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), panel);
            check(bounds.x >= 0 && bounds.x + bounds.width <= width, "Play button fits at width " + width);
            check(bounds.y >= 0 && bounds.y + bounds.height <= panel.getHeight(), "Play button fits vertically");
            check(button.getFontMetrics(button.getFont()).stringWidth(button.getText())
                    <= button.getWidth() - button.getInsets().left - button.getInsets().right, "Button label is not clipped");
        }
        BufferedImage image = new BufferedImage(width * 2, panel.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.scale(2, 2);
        graphics.setColor(Theme.WHITE); graphics.fillRect(0, 0, width, panel.getHeight());
        panel.printAll(graphics); graphics.dispose();
        if (file != null) ImageIO.write(image, "png", file.toFile());
    }
    private static void invalidate(Container parent) {
        parent.invalidate(); for (Component child : parent.getComponents()) if (child instanceof Container nested) invalidate(nested);
    }
    private static void layout(Container parent) {
        parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container nested) layout(nested);
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
