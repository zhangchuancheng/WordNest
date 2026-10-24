package wordnest;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Production Swing tray controls rendered offscreen; no Window or tray registration. */
public final class TrayMenuFontTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            List<String> actions = new ArrayList<>();
            List<Boolean> changes = new ArrayList<>();
            TrayPopup.Controls controls = TrayPopup.createControls(true,
                    () -> actions.add("show"), changes::add,
                    () -> actions.add("settings"), () -> actions.add("exit"));
            JPanel panel = controls.panel();
            List<String> labels = List.of("显示拾词", "复制查词", "窗口与后台设置…", "退出拾词");
            check(panel.getComponentCount() == 4, "All tray actions present");
            check(controls.clipboard().isSelected(), "Enabled copy lookup retained");
            for (int i = 0; i < 4; i++) {
                AbstractButton item = (AbstractButton) panel.getComponent(i);
                check(labels.get(i).equals(item.getText()), "Chinese label intact");
                check(item.getFont().canDisplayUpTo(item.getText()) == -1, "Swing font covers label");
                check(item.getInputMap().get(KeyStroke.getKeyStroke("ENTER")) != null, "Keyboard selection supported");
                check(item.getInputMap().get(KeyStroke.getKeyStroke("DOWN")) != null, "Arrow navigation supported");
            }
            controls.clipboard().setSelected(false);
            check(changes.isEmpty(), "State synchronization emits no callback");
            controls.clipboard().doClick(); controls.clipboard().doClick();
            check(changes.equals(List.of(true, false)), "Actual toggles notify both states");
            ((AbstractButton) panel.getComponent(0)).doClick();
            ((AbstractButton) panel.getComponent(2)).doClick();
            ((AbstractButton) panel.getComponent(3)).doClick();
            check(actions.equals(List.of("show", "settings", "exit")), "Callbacks remain distinct");
            Dimension size = panel.getPreferredSize(); panel.setSize(size); panel.doLayout();
            for (Component item : panel.getComponents()) {
                check(item.getWidth() > 0 && item.getX() + item.getWidth() <= size.width, "Item fits panel");
            }
            BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
            try {
                Path output = Path.of("build", "tray-menu-preview.png"); Files.createDirectories(output.getParent());
                ImageIO.write(image, "png", output.toFile());
            } catch (Exception e) { throw new RuntimeException(e); }
            for (Rectangle screen : List.of(new Rectangle(0,0,1920,1040), new Rectangle(-1920,-500,1920,1080))) {
                for (Point click : List.of(new Point(screen.x, screen.y), new Point(screen.x+screen.width,screen.y+screen.height))) {
                    Point p = TrayPopup.position(click.x,click.y,size,screen);
                    check(screen.contains(new Rectangle(p,size)), "Popup stays in monitor working area");
                }
            }
            TrayPopup popup = new TrayPopup(() -> {}, ignored -> {}, () -> {}, () -> {});
            popup.setClipboardEnabled(true); popup.close(); popup.close();
            check(Window.getWindows().length == 0, "No windows created by construction, synchronization, close or preview");
        });
        System.out.println("PASS: " + assertions + " Swing tray controls/offscreen assertions; no desktop interaction");
    }
    private static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
}
