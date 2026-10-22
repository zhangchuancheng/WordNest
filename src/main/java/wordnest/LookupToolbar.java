package wordnest;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Compact, window-independent navigation shared with headless layout previews. */
final class LookupToolbar extends JPanel {
    LookupToolbar(JToggleButton lookup, JToggleButton library, JToggleButton pin,
                  JToggleButton watch, JButton help) {
        super(new BorderLayout(5, 0)); setOpaque(false); Theme.pad(this, 12, 18, 12, 18);
        ButtonGroup pages = new ButtonGroup(); pages.add(lookup); pages.add(library); lookup.setSelected(true);
        JPanel tabs = Theme.panel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        tabs.add(lookup); tabs.add(library);
        JPanel modes = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        modes.add(pin); modes.add(watch); modes.add(help);
        for (AbstractButton button : new AbstractButton[]{lookup, library, pin, watch, help}) {
            button.setFont(Theme.font(Font.PLAIN, 12)); button.setBorder(new EmptyBorder(8, 9, 8, 9));
        }
        add(tabs, BorderLayout.WEST); add(modes, BorderLayout.EAST);
    }
}
