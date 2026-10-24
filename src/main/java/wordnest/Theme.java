package wordnest;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.ui.FlatNativeWindowsLibrary;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

final class Theme {
    static final Color PAPER = new Color(0xF4F7F3);
    static final Color INK = new Color(0x243D37);
    static final Color MUTED = new Color(0x73817A);
    static final Color GREEN = new Color(0x276E59);
    static final Color PALE = new Color(0xE5EEE8);
    static final Color LINE = new Color(0xDFE5DE);
    static final Color WHITE = new Color(0xFFFFFF);
    static Font font(int style, int size) { return new Font("Microsoft YaHei UI", style, size); }
    static Font latin(int style, int size) { return new Font("Segoe UI", style, size); }

    static void install() {
        // Keep the title bar local to this application; do not change Windows colors.
        // FlatLaf retains native Windows movement, resize, snap and system buttons.
        UIManager.put("TitlePane.useWindowDecorations", true);
        if (!FlatLightLaf.setup()) throw new IllegalStateException("Could not initialize the 词巢 theme");
        // Also provide Swing decorations if native decoration support is unavailable.
        JFrame.setDefaultLookAndFeelDecorated(true);
        JDialog.setDefaultLookAndFeelDecorated(true);
        UIManager.put("defaultFont", font(Font.PLAIN, 13));
        for (String key : new String[]{"Label.font", "Button.font", "ToggleButton.font", "TextField.font", "TextArea.font", "List.font", "OptionPane.messageFont", "OptionPane.buttonFont"}) {
            UIManager.put(key, font(Font.PLAIN, 13));
        }
        UIManager.put("Panel.background", PAPER);
        UIManager.put("control", PAPER);
        UIManager.put("RootPane.background", PAPER);
        UIManager.put("OptionPane.background", PAPER);
        UIManager.put("TextField.caretForeground", GREEN);
        UIManager.put("ScrollBar.width", 9);
        UIManager.put("Component.focusColor", GREEN);
        UIManager.put("Component.accentColor", GREEN);
        UIManager.put("TextComponent.selectionBackground", PALE);
        UIManager.put("TextComponent.selectionForeground", INK);
        UIManager.put("TitlePane.unifiedBackground", false);
        UIManager.put("TitlePane.background", PAPER);
        UIManager.put("TitlePane.inactiveBackground", PAPER);
        UIManager.put("TitlePane.foreground", GREEN);
        UIManager.put("TitlePane.inactiveForeground", MUTED);
        UIManager.put("TitlePane.borderColor", LINE);
        UIManager.put("TitlePane.buttonHoverBackground", PALE);
        UIManager.put("TitlePane.buttonPressedBackground", new Color(0xD5E5DA));
        UIManager.put("TitlePane.closeHoverBackground", PALE);
        UIManager.put("TitlePane.closePressedBackground", new Color(0xD5E5DA));
        UIManager.put("TitlePane.closeHoverForeground", GREEN);
        UIManager.put("TitlePane.closePressedForeground", GREEN);
        UIManager.put("TitlePane.font", font(Font.PLAIN, 13));
        UIManager.put("TitlePane.centerTitle", false);
        UIManager.put("TitlePane.menuBarEmbedded", false);
        UIManager.put("TitlePane.buttonSize", new Dimension(42, 32));
    }

    /** Validates packaged styling and dependencies without constructing any Window. */
    static Properties verify() throws Exception {
        install();
        if (!(UIManager.getLookAndFeel() instanceof FlatLightLaf)) {
            throw new IllegalStateException("FlatLaf did not become the active look and feel");
        }
        if (!PAPER.equals(UIManager.getColor("TitlePane.background"))
                || !PAPER.equals(UIManager.getColor("TitlePane.inactiveBackground"))
                || !GREEN.equals(UIManager.getColor("TitlePane.foreground"))
                || !MUTED.equals(UIManager.getColor("TitlePane.inactiveForeground"))
                || UIManager.getBoolean("TitlePane.unifiedBackground")
                || !UIManager.getBoolean("TitlePane.useWindowDecorations")) {
            throw new IllegalStateException("Title bar colors or decoration configuration do not match the theme");
        }
        Path library = Path.of(FlatLightLaf.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        verifyHash(library, "917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4");
        Path nativeLibrary = library.resolveSibling("flatlaf-3.7.2-windows-x86_64.dll");
        verifyHash(nativeLibrary, "533407841892e294f70e4f1443ebddecbea83b7f0bd2af876181505073b677d5");
        Properties result = new Properties();
        result.setProperty("ui.lookAndFeel", UIManager.getLookAndFeel().getClass().getName());
        result.setProperty("ui.flatlaf.version", "3.7.2");
        result.setProperty("ui.title.background", "#F4F7F3");
        result.setProperty("ui.title.foreground", "#276E59");
        result.setProperty("ui.title.inactiveBackground", "#F4F7F3");
        result.setProperty("ui.title.inactiveForeground", "#73817A");
        result.setProperty("ui.dependencies", "SHA-256 verified");
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            boolean loaded = FlatNativeWindowsLibrary.isLoaded();
            result.setProperty("ui.nativeLibrary.loaded", Boolean.toString(loaded));
            if (!loaded) throw new IllegalStateException("The Windows title bar library did not load");
        }
        return result;
    }

    private static void verifyHash(Path file, String expected) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        if (!expected.equals(actual)) throw new IllegalStateException("Theme dependency checksum mismatch: " + file.getFileName());
    }

    static JPanel panel(LayoutManager layout) {
        JPanel p = new JPanel(layout); p.setOpaque(false); return p;
    }
    static JLabel label(String text, int size, Color color) {
        JLabel l = new JLabel(text); l.setFont(font(Font.PLAIN, size)); l.setForeground(color); return l;
    }
    static void pad(JComponent c, int top, int left, int bottom, int right) {
        c.setBorder(new EmptyBorder(top, left, bottom, right));
    }
    static JButton button(String text, boolean primary) {
        return new SoftButton(text, primary);
    }
    static JToggleButton toggle(String text) {
        JToggleButton b = new JToggleButton(text) {
            @Override protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(isSelected() ? PALE : (getModel().isRollover() ? new Color(0xEEF0EB) : PAPER));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12); g.dispose();
                super.paintComponent(graphics);
            }
        };
        b.setContentAreaFilled(false); b.setFocusPainted(false); b.setBorderPainted(false);
        b.setForeground(GREEN); b.setFont(font(Font.PLAIN, 12)); b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(new EmptyBorder(7, 12, 7, 12));
        return b;
    }
    static JTextArea text(String value, int size, Color color, int width) {
        JTextArea a = new JTextArea(value);
        a.setEditable(false); a.setOpaque(false); a.setLineWrap(true); a.setWrapStyleWord(true);
        a.setFont(font(Font.PLAIN, size)); a.setForeground(color); a.setBorder(null);
        FontMetrics fm = a.getFontMetrics(a.getFont());
        int lines = 0;
        for (String line : value.split("\n", -1)) lines += Math.max(1, (int)Math.ceil((double)fm.stringWidth(line) / Math.max(100, width)));
        a.setRows(lines); a.setAlignmentX(Component.LEFT_ALIGNMENT);
        a.setMaximumSize(new Dimension(Integer.MAX_VALUE, fm.getHeight() * lines + 12));
        return a;
    }
    static final class SoftButton extends JButton {
        private final boolean primary;
        SoftButton(String text, boolean primary) {
            super(text); this.primary = primary;
            setContentAreaFilled(false); setFocusPainted(false); setBorderPainted(false);
            setForeground(primary ? WHITE : GREEN); setFont(font(Font.PLAIN, 13));
            setBorder(new EmptyBorder(9, 15, 9, 15)); setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = primary ? GREEN : PALE;
            if (getModel().isRollover()) fill = primary ? new Color(0x1D5B49) : new Color(0xD5E5DA);
            if (!isEnabled()) fill = LINE;
            g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), 13, 13);
            if (hasFocus()) { g.setColor(primary ? new Color(0xBBD9C8) : GREEN); g.drawRoundRect(2, 2, getWidth()-5, getHeight()-5, 10, 10); }
            g.dispose(); super.paintComponent(graphics);
        }
    }
    static final class Card extends JPanel {
        Card(LayoutManager layout) { super(layout); setOpaque(false); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(WHITE); g.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 18, 18);
            g.setColor(LINE); g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 18, 18);
            g.dispose(); super.paintComponent(graphics);
        }
    }
}
