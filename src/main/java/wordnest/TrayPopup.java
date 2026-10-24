package wordnest;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

/** Swing-rendered tray actions; the small ownerless window exists only while open. */
final class TrayPopup implements AutoCloseable {
    private final Runnable showWindow, openSettings, exitApplication;
    private final Consumer<Boolean> clipboardChanged;
    private JDialog window;
    private Controls controls;

    TrayPopup(Runnable showWindow, Consumer<Boolean> clipboardChanged,
              Runnable openSettings, Runnable exitApplication) {
        this.showWindow = showWindow;
        this.clipboardChanged = clipboardChanged;
        this.openSettings = openSettings;
        this.exitApplication = exitApplication;
    }

    void show(int x, int y, boolean enabled) {
        close();
        controls = createControls(enabled, () -> run(showWindow),
                value -> run(() -> clipboardChanged.accept(value)),
                () -> run(openSettings), () -> run(exitApplication));
        GraphicsConfiguration screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().contains(x, y)) {
                screen = device.getDefaultConfiguration(); break;
            }
        }
        JDialog dialog = new JDialog((Window) null, "拾词菜单", Dialog.ModalityType.MODELESS, screen);
        window = dialog;
        dialog.setUndecorated(true);
        dialog.setType(Window.Type.POPUP);
        dialog.setAlwaysOnTop(true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(controls.panel());
        dialog.getRootPane().registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { if (window == dialog) close(); }
        });
        dialog.pack();
        Rectangle usable = screen.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(screen);
        usable = new Rectangle(usable.x + insets.left, usable.y + insets.top,
                usable.width - insets.left - insets.right, usable.height - insets.top - insets.bottom);
        dialog.setLocation(position(x, y, dialog.getSize(), usable));
        dialog.setVisible(true);
        controls.panel().getComponent(0).requestFocusInWindow();
    }

    static Point position(int x, int y, Dimension size, Rectangle bounds) {
        return new Point(Math.max(bounds.x, Math.min(x, bounds.x + bounds.width - size.width)),
                Math.max(bounds.y, Math.min(y, bounds.y + bounds.height - size.height)));
    }

    void setClipboardEnabled(boolean enabled) {
        if (controls != null) controls.clipboard().setSelected(enabled);
    }

    private void run(Runnable action) { close(); action.run(); }

    @Override public void close() {
        JDialog prior = window;
        window = null;
        controls = null;
        if (prior != null) prior.dispose();
    }

    record Controls(JPanel panel, JCheckBox clipboard) { }

    /** Actual controls shared by the popup and offscreen regression tests. */
    static Controls createControls(boolean enabled, Runnable showWindow,
                                   Consumer<Boolean> clipboardChanged, Runnable openSettings,
                                   Runnable exitApplication) {
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 4));
        panel.setBackground(Theme.PAPER);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.LINE),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        JButton show = action("显示拾词", showWindow);
        JCheckBox clipboard = new JCheckBox("复制查词", enabled);
        clipboard.setFont(Theme.font(Font.PLAIN, 13));
        clipboard.setForeground(Theme.INK); clipboard.setOpaque(false);
        clipboard.addActionListener(e -> clipboardChanged.accept(clipboard.isSelected()));
        JButton settings = action("窗口与后台设置…", openSettings);
        JButton exit = action("退出拾词", exitApplication);
        AbstractButton[] items = {show, clipboard, settings, exit};
        for (int i = 0; i < items.length; i++) {
            AbstractButton item = items[i];
            item.setPreferredSize(new Dimension(210, 34));
            panel.add(item);
            final int index = i;
            for (int direction : new int[]{-1, 1}) {
                String key = "tray-move-" + direction;
                item.getInputMap().put(KeyStroke.getKeyStroke(direction < 0 ? KeyEvent.VK_UP : KeyEvent.VK_DOWN, 0), key);
                item.getActionMap().put(key, new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent e) {
                        items[Math.floorMod(index + direction, items.length)].requestFocusInWindow();
                    }
                });
            }
            item.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "tray-select");
            item.getActionMap().put("tray-select", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { item.doClick(); }
            });
        }
        return new Controls(panel, clipboard);
    }

    private static JButton action(String label, Runnable action) {
        JButton button = new JButton(label);
        button.setFont(Theme.font(Font.PLAIN, 13));
        button.setForeground(Theme.INK);
        button.setBackground(Theme.WHITE);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.addActionListener(e -> action.run());
        return button;
    }
}
