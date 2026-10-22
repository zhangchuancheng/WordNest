package wordnest;

import com.formdev.flatlaf.FlatLightLaf;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Properties;

/** Headless theme and packaged native dependency checks; never creates a Window. */
public final class ThemeTest {
    public static void main(String[] args) throws Exception {
        if (!GraphicsEnvironment.isHeadless()) throw new AssertionError("Run with -Djava.awt.headless=true");
        Properties result = Theme.verify();
        SwingUtilities.invokeAndWait(() -> {
            if (!(UIManager.getLookAndFeel() instanceof FlatLightLaf)) throw new AssertionError("Wrong look and feel");
            if (!JFrame.isDefaultLookAndFeelDecorated() || !JDialog.isDefaultLookAndFeelDecorated()) {
                throw new AssertionError("Swing fallback decoration disabled");
            }
            if (!Theme.PALE.equals(UIManager.getColor("TitlePane.buttonHoverBackground"))
                    || !Theme.GREEN.equals(UIManager.getColor("TitlePane.closeHoverForeground"))) {
                throw new AssertionError("Title bar hover palette mismatch");
            }
            JPanel panel = new JPanel(new BorderLayout());
            panel.add(Theme.button("查询", true), BorderLayout.WEST);
            panel.add(new JTextField("coordinate"), BorderLayout.CENTER);
            panel.setSize(480, 48);
            panel.doLayout();
            BufferedImage image = new BufferedImage(480, 48, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try { panel.printAll(graphics); } finally { graphics.dispose(); }
            if (Window.getWindows().length != 0) throw new AssertionError("Theme verification created a window");
        });
        result.store(System.out, "ThemeTest PASS - zero windows created");
    }
}
