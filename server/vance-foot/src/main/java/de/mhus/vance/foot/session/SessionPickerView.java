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
import de.mhus.vance.api.ws.SessionSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Lanterna fullscreen picker used by {@code --resume}. Renders an
 * {@link ActionListBox} of candidate sessions (icon · title · project ·
 * profile · last-seen · session-id). Enter selects, Esc cancels.
 *
 * <p>Holds no state of its own beyond the candidate list; the picked
 * session id is returned from {@link #show(WindowBasedTextGUI, String, List)}.
 */
public final class SessionPickerView {

    private SessionPickerView() {}

    /**
     * Opens the picker and blocks until the user selects a row or
     * cancels. Returns the picked session, or {@code null} on Esc.
     */
    public static @Nullable SessionSummary show(WindowBasedTextGUI gui,
                                                 String title,
                                                 List<SessionSummary> candidates) {
        if (candidates.isEmpty()) return null;

        // Holder we mutate from the action callbacks. Lanterna's
        // ActionListBox doesn't expose a "selected on enter" API
        // separate from the per-row Runnable.
        SessionSummary[] picked = new SessionSummary[] { null };
        BasicWindow window = new BasicWindow(title);
        window.setHints(Set.of(Window.Hint.CENTERED, Window.Hint.FIT_TERMINAL_WINDOW));

        Panel panel = new Panel(new LinearLayout(com.googlecode.lanterna.gui2.Direction.VERTICAL));
        panel.addComponent(new Label(formatHeader()).addStyle(SGR.BOLD));

        ActionListBox listBox = new ActionListBox();
        for (SessionSummary s : candidates) {
            String row = formatRow(s);
            listBox.addItem(row, () -> {
                picked[0] = s;
                window.close();
            });
        }
        panel.addComponent(listBox.withBorder(Borders.singleLine()));

        Label hint = new Label("[Enter] resume   [Esc] cancel");
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
        return String.format("%-22s %-7s %-12s %-10s   %s",
                "TITLE / NAME", "PROFILE", "LAST SEEN", "PROJECT", "SESSION");
    }

    private static String formatRow(SessionSummary s) {
        String icon = (s.getIcon() != null && !s.getIcon().isBlank()) ? s.getIcon() + " " : "";
        String label;
        if (s.getTitle() != null && !s.getTitle().isBlank()) {
            label = icon + s.getTitle();
        } else if (s.getDisplayName() != null && !s.getDisplayName().isBlank()) {
            label = icon + s.getDisplayName();
        } else {
            label = icon + "(unnamed)";
        }
        return String.format("%-22s %-7s %-12s %-10s   %s",
                truncate(label, 22),
                truncate(nullable(s.getProfile()), 7),
                relativeTime(s.getLastActivityAt()),
                truncate(nullable(s.getProjectId()), 10),
                tailId(s.getSessionId()));
    }

    private static String relativeTime(long epochMillis) {
        if (epochMillis <= 0) return "—";
        Duration d = Duration.between(Instant.ofEpochMilli(epochMillis), Instant.now());
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
