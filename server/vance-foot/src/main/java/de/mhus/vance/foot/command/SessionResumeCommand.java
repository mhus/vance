package de.mhus.vance.foot.command;

import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionListRequest;
import de.mhus.vance.api.ws.SessionListResponse;
import de.mhus.vance.api.ws.SessionResumeRequest;
import de.mhus.vance.api.ws.SessionResumeResponse;
import de.mhus.vance.api.ws.SessionSummary;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /session-resume [sessionId]} — resumes a session and binds it to
 * the live connection.
 *
 * <p>With an explicit {@code sessionId} the command goes straight to the
 * Brain. Without one, it fetches the user's session list and pops a Lanterna
 * fullscreen picker (the first hybrid-TUI menu in {@code vance-foot}). Bound
 * sessions are filtered out — they are not resumable until released — and
 * the remaining list is sorted by most-recent activity first.
 */
@Component
public class SessionResumeCommand implements SlashCommand {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;
    private final InterfaceService ui;

    public SessionResumeCommand(ConnectionService connection,
                                SessionService sessions,
                                ChatTerminal terminal,
                                InterfaceService ui) {
        this.connection = connection;
        this.sessions = sessions;
        this.terminal = terminal;
        this.ui = ui;
    }

    @Override
    public String name() {
        return "session-resume";
    }

    @Override
    public String description() {
        return "Resume a session and bind it. Args: [sessionId] (omit to pick from a list).";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.size() > 1) {
            terminal.error("Usage: /session-resume [sessionId]");
            return;
        }
        String sessionId = args.isEmpty() ? pickSessionInteractively() : args.get(0);
        if (sessionId == null) {
            return;
        }
        SessionResumeResponse response = connection.request(
                MessageType.SESSION_RESUME,
                SessionResumeRequest.builder().sessionId(sessionId).build(),
                SessionResumeResponse.class,
                Duration.ofSeconds(10));

        sessions.bind(response.getSessionId(), response.getProjectId());
        terminal.info("Session resumed: " + response.getSessionId()
                + " (project=" + response.getProjectId() + ")");
    }

    /**
     * Returns the chosen sessionId, or {@code null} if the user cancelled or
     * there was nothing to pick.
     */
    private @Nullable String pickSessionInteractively() throws Exception {
        SessionListResponse list = connection.request(
                MessageType.SESSION_LIST,
                SessionListRequest.builder().build(),
                SessionListResponse.class,
                Duration.ofSeconds(10));

        List<SessionSummary> all = list.getSessions();
        if (all == null || all.isEmpty()) {
            terminal.info("No sessions to resume.");
            return null;
        }

        List<SessionSummary> available = all.stream()
                .filter(s -> !s.isBound())
                .sorted(Comparator.comparingLong(SessionSummary::getLastActivityAt).reversed())
                .toList();
        int hidden = all.size() - available.size();
        if (available.isEmpty()) {
            terminal.info("No resumable sessions — all "
                    + all.size() + " are currently bound to another connection.");
            return null;
        }

        AtomicReference<@Nullable String> selected = new AtomicReference<>();
        ui.runFullscreen(session -> {
            BasicWindow window = new BasicWindow("Resume session");
            window.setHints(Set.of(Window.Hint.CENTERED));

            ActionListBox listBox = new ActionListBox();
            for (SessionSummary s : available) {
                String label = formatRow(s);
                String id = s.getSessionId();
                listBox.addItem(label, () -> {
                    selected.set(id);
                    window.close();
                });
            }

            Panel content = new Panel();
            content.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            content.addComponent(new Label(
                    "Select a session — Enter to resume, Esc/Cancel to keep current."));
            if (hidden > 0) {
                content.addComponent(new Label(
                        "(" + hidden + " bound session" + (hidden == 1 ? "" : "s")
                                + " hidden — not resumable.)"));
            }
            content.addComponent(listBox.withBorder(Borders.singleLine()));
            content.addComponent(new Button("Cancel", window::close));
            window.setComponent(content);

            window.setCloseWindowWithEscape(true);
            session.gui().addWindowAndWait(window);
        });
        return selected.get();
    }

    private static String formatRow(SessionSummary s) {
        String displayName = Objects.toString(s.getDisplayName(), "");
        return String.format("%-9s  %-20s  %-11s  %-20s  %s",
                truncate(Objects.toString(s.getStatus(), ""), 9),
                truncate(Objects.toString(s.getProjectId(), ""), 20),
                TIME.format(Instant.ofEpochMilli(s.getLastActivityAt())),
                truncate(displayName, 20),
                s.getSessionId());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
