package wordnest;

import java.awt.CheckboxMenuItem;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Uses the production menu factory without native peers or desktop interaction.
 * Run with -Djava.awt.headless=false because AWT MenuComponent rejects headless mode.
 * Only detached menu objects are constructed: no addNotify(), show(), TrayIcon,
 * SystemTray registration, Window construction, clipboard access, or input injection.
 * Font inheritance and glyph coverage do not prove native Windows menu rendering.
 */
public final class TrayMenuFontTest {
    private static int assertions;
    private static final List<String> LABELS = List.of(
            "显示拾词", "复制查词", "窗口与后台设置…", "退出拾词");

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new AssertionError("Run with -Djava.awt.headless=false; only detached menu objects are created");
        }
        check(Window.getWindows().length == 0, "Test begins without any Window");

        List<String> actions = new ArrayList<>();
        List<Boolean> clipboardChanges = new ArrayList<>();
        TraySupport.MenuControls controls = TraySupport.createMenu(true,
                () -> actions.add("show"), clipboardChanges::add,
                () -> actions.add("settings"), () -> actions.add("exit"));
        PopupMenu popup = controls.popup();
        CheckboxMenuItem clipboard = controls.clipboard();
        check(popup.getParent() == null, "Popup remains detached from any Window or tray");
        check(popup.getItemCount() == 5, "Four actions and one separator remain available");
        check(popup.getItem(1) == clipboard, "Returned clipboard control is the actual menu checkbox");
        check(clipboard.getState(), "Factory preserves an enabled clipboard preference");
        check("-".equals(popup.getItem(3).getLabel()), "Exit remains separated from other actions");

        List<MenuItem> items = List.of(popup.getItem(0), popup.getItem(1), popup.getItem(2), popup.getItem(4));
        Font font = popup.getFont();
        check(font != null, "Popup explicitly supplies a font rather than the AWT default");
        check(font.equals(Theme.font(Font.PLAIN, 13)), "Tray font matches the Chinese application font");
        check("Microsoft YaHei UI".equalsIgnoreCase(font.getFamily(Locale.ENGLISH)),
                "Requested physical Chinese font resolves without silent Dialog substitution: " + font);
        check(font.getStyle() == Font.PLAIN && font.getSize() == 13, "Tray uses readable plain 13 point type");
        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.get(i);
            String label = LABELS.get(i);
            check(label.equals(item.getLabel()), "Production Chinese menu label is intact: " + label);
            check(item.getParent() == popup, "Menu entry belongs to the font-owning popup: " + label);
            check(font.equals(item.getFont()), "Ordinary and checkbox items inherit the popup font: " + label);
            check(item.getFont().canDisplayUpTo(label) == -1,
                    "Resolved font covers every character, including ellipsis: " + label);
        }

        // Prove inheritance rather than merely comparing coincidentally equal initial fonts.
        Font changed = font.deriveFont(15f);
        popup.setFont(changed);
        for (MenuItem item : items) {
            check(changed.equals(item.getFont()), "Popup font changes reach existing child: " + item.getLabel());
        }
        popup.setFont(font);
        check(font.equals(clipboard.getFont()), "Checkbox inherits the restored font too");

        check(actions.isEmpty() && clipboardChanges.isEmpty(), "Constructing and styling menus fires no user callback");
        fireAction(popup.getItem(0));
        fireAction(popup.getItem(2));
        fireAction(popup.getItem(4));
        check(actions.equals(List.of("show", "settings", "exit")), "Menu actions retain distinct callback wiring");
        clipboard.setState(false);
        check(clipboardChanges.isEmpty(), "Programmatic checkbox synchronization does not notify callbacks");
        fireClipboardChange(clipboard, ItemEvent.DESELECTED);
        clipboard.setState(true);
        fireClipboardChange(clipboard, ItemEvent.SELECTED);
        check(clipboardChanges.equals(List.of(false, true)), "Checkbox reports both actual item-event states");

        TraySupport.MenuControls disabled = TraySupport.createMenu(false,
                () -> actions.add("unexpected-show"), clipboardChanges::add,
                () -> actions.add("unexpected-settings"), () -> actions.add("unexpected-exit"));
        check(!disabled.clipboard().getState(), "Factory also preserves a disabled clipboard preference");
        check(font.equals(disabled.clipboard().getFont()), "Disabled clipboard preference retains the Chinese font");
        check(actions.equals(List.of("show", "settings", "exit"))
                        && clipboardChanges.equals(List.of(false, true)),
                "A second menu construction does not fire callbacks");
        check(Window.getWindows().length == 0, "Menu font regression test creates no Window");
        System.out.println("PASS: " + assertions
                + " detached AWT menu font/callback assertions; no peers, windows, tray registration, or desktop input");
    }

    private static void fireAction(MenuItem item) {
        var listeners = item.getActionListeners();
        check(listeners.length == 1, "Each action has exactly one callback: " + item.getLabel());
        ActionEvent event = new ActionEvent(item, ActionEvent.ACTION_PERFORMED, item.getActionCommand());
        for (var listener : listeners) listener.actionPerformed(event);
    }

    private static void fireClipboardChange(CheckboxMenuItem item, int state) {
        var listeners = item.getItemListeners();
        check(listeners.length == 1, "Clipboard has exactly one item-state callback");
        ItemEvent event = new ItemEvent(item, ItemEvent.ITEM_STATE_CHANGED, item.getLabel(), state);
        for (var listener : listeners) listener.itemStateChanged(event);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
