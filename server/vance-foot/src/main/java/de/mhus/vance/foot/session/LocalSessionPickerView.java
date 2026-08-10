package de.mhus.vance.foot.session;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import de.mhus.vance.foot.auth.SessionAnchor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Lanterna fullscreen picker for the {@code -c} / {@code --continue}
 * startup path when the local {@code .vancetope/session.yaml} history has
 * more than one session and no {@code --name} / {@code --session} disambiguates.
 *
 * <p>Unlike {@link SessionPickerView} (which lists every matching session
 * on the server), this picker shows <em>only</em> the sessions recorded in
 * the local history, plus three trailing action rows so the user can escape
 * the local list when the session they want isn't there:
 * {@code All Sessions} (fall back to the full server picker),
 * {@code New Session} (start fresh), and {@code Cancel}.
 *
 * <p>Holds no state of its own; the picked choice is returned from
 * {@link #show(WindowBasedTextGUI, String, List)}.
 */
public final class LocalSessionPickerView {

    private LocalSessionPickerView() {}

    /** What the user picked: a local session, or one of the escape actions. */
    public enum Choice {
        RESUME_ENTRY,
        ALL_SESSIONS,
        NEW_SESSION,
        CANCEL
    }

    /** Result of the picker: the {@link Choice} plus the selected local entry (only for RESUME_ENTRY). */
    public record Result(Choice choice, SessionAnchor.@Nullable SessionEntry entry) {}

    /**
     * Opens the picker and blocks until the user selects a row or cancels.
     * Returns the chosen result, or {@code null} on Esc / window close.
     */
    public static @Nullable Result show(WindowBasedTextGUI gui,
                                        String title,
                                        List<SessionAnchor.SessionEntry> entries) {
        if (entries.isEmpty()) return null;

        Result[] picked = new Result[] { null };
        BasicWindow window = new BasicWindow(title);
        window.setHints(Set.of(Window.Hint.CENTERED, Window.Hint.FIT_TERMINAL_WINDOW));

        Panel panel = new Panel(new LinearLayout(com.googlecode.lanterna.gui2.Direction.VERTICAL));
        panel.addComponent(new Label(formatHeader()).addStyle(SGR.BOLD));

        ActionListBox listBox = new ActionListBox();
        for (SessionAnchor.SessionEntry e : entries) {
            String row = formatRow(e);
            listBox.addItem(row, () -> {
                picked[0] = new Result(Choice.RESUME_ENTRY, e);
                window.close();
            });
        }
        // Escape rows — only when there is more than one local session the
        // picker is worth opening at all, so these always follow the entries.
        listBox.addItem("── All Sessions ──", () -> {
            picked[0] = new Result(Choice.ALL_SESSIONS, null);
            window.close();
        });
        listBox.addItem("── New Session ──", () -> {
            picked[0] = new Result(Choice.NEW_SESSION, null);
            window.close();
        });
        listBox.addItem("── Cancel ──", () -> {
            picked[0] = new Result(Choice.CANCEL, null);
            window.close();
        });
        panel.addComponent(listBox.withBorder(Borders.singleLine()));

        Label hint = new Label("[Enter] select   [Esc] cancel");
        hint.setForegroundColor(TextColor.ANSI.WHITE_BRIGHT);
        panel.addComponent(hint);

        window.setComponent(panel);
        window.setCloseWindowWithEscape(true);

        // Esc isn't tied to a specific component — let it be the cancel key.
        window.addWindowListener(new com.googlecode.lanterna.gui2.WindowListenerAdapter() {
            @Override
            public void onInput(Window basePane, KeyStroke keyStroke, java.util.concurrent.atomic.AtomicBoolean deliverEvent) {
                if (keyStroke.getKeyType() == KeyType.Escape) {
                    window.close();
                    deliverEvent.set(false);
                }
            }
        });

        gui.addWindowAndWait(window);
        return picked[0];
    }

    private static String formatHeader() {
        return String.format("%-22s %-10s %-12s   %s",
                "NAME / ID", "PROJECT", "LAST SEEN", "SESSION");
    }

    private static String formatRow(SessionAnchor.SessionEntry e) {
        String label;
        if (e.getName() != null && !e.getName().isBlank()) {
            label = e.getName();
        } else {
            label = "(unnamed)";
        }
        return String.format("%-22s %-10s %-12s   %s",
                truncate(label, 22),
                truncate(nullable(e.getProjectId()), 10),
                relativeTime(e.getUpdatedAt()),
                tailId(e.getSessionId()));
    }

    private static String relativeTime(@Nullable Long updatedAt) {
        if (updatedAt == null || updatedAt <= 0) return "—";
        Duration d = Duration.between(Instant.ofEpochMilli(updatedAt), Instant.now());
        long secs = d.getSeconds();
        if (secs < 60) return secs + "s ago";
        if (secs < 3600) return (secs / 60) + "m ago";
        if (secs < 86_400) return (secs / 3600) + "h ago";
        return (secs / 86_400) + "d ago";
    }

    private static String tailId(@Nullable String id) {
        if (id == null) return "";
        if (id.length() <= 12) return id;
        return "…" + id.substring(id.length() - 11);
    }

    private static String nullable(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}