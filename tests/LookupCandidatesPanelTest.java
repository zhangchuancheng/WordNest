package wordnest;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real candidate JPanel and note/history coordinator, without constructing a Window. */
public final class LookupCandidatesPanelTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Headless only");
        Path output = Path.of(args[0]); Files.createDirectories(output);
        LookupResolver resolver = LookupResolverTest.resolver("axes\taxe\naxes\taxis\n", "current", "axe paper", "axis paper");
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            try {
                LookupHistory history = LookupHistory.sessionOnly(); AtomicBoolean canSave = new AtomicBoolean(true);
                WordEntry[] displayed = {null};
                LookupSession session = new LookupSession(history, value -> resolver.resolve(value).candidates().stream().findFirst(),
                        canSave::get, value -> displayed[0] = value);
                session.visit(resolver.resolve("current").candidates().get(0), false);
                LookupCandidatesPanel panel = new LookupCandidatesPanel(value -> session.visit(resolver.resolve(value).candidates().get(0), true));
                check(!panel.isVisible(), "Candidate strip starts hidden");
                panel.showCandidates(resolver.resolve("axes paper"));
                JComboBox<?> choices = (JComboBox<?>) named(panel, "lookup-candidate-options");
                JButton open = (JButton) named(panel, "lookup-candidate-open");
                check(panel.isVisible() && choices.getItemCount() == 3 && choices.getSelectedIndex() == 0, "All real alternatives are shown behind an unselected placeholder");
                check(!open.isEnabled() && displayed[0].word().equals("current") && history.recent().isEmpty(), "Displaying ambiguous candidates neither chooses an entry nor records history");
                choices.setSelectedIndex(2);
                check(open.isEnabled() && displayed[0].word().equals("current"), "Selecting a candidate does not open it until explicit confirmation");
                canSave.set(false); open.doClick();
                check(displayed[0].word().equals("current") && history.recent().isEmpty(), "Failed pending-note save blocks candidate navigation and history advancement");
                check(panel.isVisible() && choices.getSelectedIndex() == 2 && open.isEnabled(), "Failed navigation retains the chosen candidate for retry");
                canSave.set(true); open.doClick();
                check(displayed[0].word().equals("axis paper") && history.recent().equals(List.of("axis paper")), "Explicit choice follows the normal note-safe navigation/history path");
                for (int width : new int[]{340, 440, 640}) render(panel, width, output.resolve("lookup-candidates-" + width + ".png"));
                ((JButton) named(panel, "lookup-candidate-dismiss")).doClick();
                check(!panel.isVisible() && displayed[0].word().equals("axis paper"), "Dismissal only hides candidates and preserves the current entry");
                panel.showCandidates(resolver.resolve("axes paper"));
                check(choices.getSelectedIndex() == 0 && !open.isEnabled(), "A later ambiguity resets selection instead of reusing a prior guess");
                check(Window.getWindows().length == 0, "No window created during integration or rendering");
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        System.out.println("PASS: " + assertions + " candidate panel/note-history assertions; widths 340/440/640; no windows");
    }
    private static void render(JPanel panel, int width, Path file) throws Exception {
        panel.setSize(width, panel.getPreferredSize().height); layout(panel);
        for (String name : List.of("lookup-candidate-options", "lookup-candidate-open", "lookup-candidate-dismiss")) {
            Component component = named(panel, name);
            Rectangle bounds = SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), panel);
            check(bounds.x >= 0 && bounds.getMaxX() <= width && bounds.getMaxY() <= panel.getHeight(), "Candidate controls stay within " + width + "px: " + name);
        }
        BufferedImage image = new BufferedImage(width * 2, panel.getHeight() * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); graphics.scale(2, 2); graphics.setColor(Theme.PAPER); graphics.fillRect(0, 0, width, panel.getHeight());
        panel.printAll(graphics); graphics.dispose(); ImageIO.write(image, "png", file.toFile());
    }
    private static void layout(Container panel) { panel.doLayout(); for (Component child : panel.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static Component named(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) { Component result = named(container, name); if (result != null) return result; }
        }
        return null;
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}
