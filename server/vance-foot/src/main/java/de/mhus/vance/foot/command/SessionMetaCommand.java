package de.mhus.vance.foot.command;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import de.mhus.vance.api.session.SessionColor;
import de.mhus.vance.api.session.SessionMetadataDto;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.SessionListRequest;
import de.mhus.vance.api.ws.SessionListResponse;
import de.mhus.vance.api.ws.SessionMetadataPatchWsRequest;
import de.mhus.vance.api.ws.SessionSummary;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.session.SessionService.BoundSession;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /session-meta} — fullscreen Lanterna form to edit the bound
 * session's user-facing metadata: title, icon, color, tags, pin.
 *
 * <p>Pre-fills from a fresh {@code session-list} fetch and sends one
 * {@code session-metadata-patch} on Save. Esc / Cancel discards. See
 * {@code specification/session-lifecycle.md} §14.
 */
@Component
public class SessionMetaCommand implements SlashCommand {

    private static final String NO_COLOR = "(none)";

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;
    private final InterfaceService ui;

    public SessionMetaCommand(
            ConnectionService connection,
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
        return "session-meta";
    }

    @Override
    public String description() {
        return "Edit session title, icon, color, tags, pin — Lanterna form.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        BoundSession bound = sessions.current();
        if (bound == null) {
            terminal.error("No session bound — use /session-resume or /session-bootstrap first.");
            return;
        }
        SessionSummary current = fetchSummary(bound.sessionId());
        if (current == null) {
            terminal.error("Could not load session metadata.");
            return;
        }
        FormResult result = openForm(current);
        if (result == null) {
            terminal.info("(cancelled)");
            return;
        }
        SessionMetadataPatchWsRequest.SessionMetadataPatchWsRequestBuilder builder =
                SessionMetadataPatchWsRequest.builder().sessionId(bound.sessionId());
        if (!equalsNullable(result.title, current.getTitle())) {
            builder.title(result.title == null ? "" : result.title);
        }
        if (!equalsNullable(result.icon, current.getIcon())) {
            builder.icon(result.icon == null ? "" : result.icon);
        }
        if (result.color != current.getColor()) {
            builder.color(result.color);
        }
        if (!equalTags(result.tags, current.getTags())) {
            builder.tags(result.tags);
        }
        if (result.pinned != current.isPinned()) {
            builder.pinned(result.pinned);
        }

        SessionMetadataDto reply = connection.request(
                MessageType.SESSION_METADATA_PATCH,
                builder.build(),
                SessionMetadataDto.class,
                Duration.ofSeconds(10));
        sessions.setMetadata(bound.sessionId(), reply.getTitle(), reply.getIcon());
        terminal.info("Updated: title='" + nullToEmpty(reply.getTitle())
                + "' icon='" + nullToEmpty(reply.getIcon())
                + "' color=" + (reply.getColor() == null ? "—" : reply.getColor().name())
                + " tags=" + reply.getTags()
                + " pinned=" + reply.isPinned());
    }

    private @Nullable SessionSummary fetchSummary(String sessionId) throws Exception {
        SessionListResponse list = connection.request(
                MessageType.SESSION_LIST,
                SessionListRequest.builder().build(),
                SessionListResponse.class,
                Duration.ofSeconds(10));
        if (list.getSessions() == null) return null;
        for (SessionSummary s : list.getSessions()) {
            if (sessionId.equals(s.getSessionId())) return s;
        }
        return null;
    }

    private @Nullable FormResult openForm(SessionSummary current) throws Exception {
        AtomicBoolean saved = new AtomicBoolean(false);
        FormResult draft = new FormResult();
        draft.title = current.getTitle();
        draft.icon = current.getIcon();
        draft.color = current.getColor();
        draft.tags = current.getTags() == null
                ? new ArrayList<>()
                : new ArrayList<>(current.getTags());
        draft.pinned = current.isPinned();

        ui.runFullscreen(session -> {
            BasicWindow window = new BasicWindow("Session metadata");
            window.setHints(Set.of(Window.Hint.CENTERED));
            window.setCloseWindowWithEscape(true);

            Panel grid = new Panel();
            grid.setLayoutManager(new GridLayout(2)
                    .setHorizontalSpacing(2)
                    .setVerticalSpacing(1));

            grid.addComponent(new Label("Title"));
            TextBox titleBox = new TextBox(
                    new TerminalSize(40, 1),
                    draft.title == null ? "" : draft.title);
            grid.addComponent(titleBox);

            grid.addComponent(new Label("Icon (emoji)"));
            TextBox iconBox = new TextBox(
                    new TerminalSize(8, 1),
                    draft.icon == null ? "" : draft.icon);
            grid.addComponent(iconBox);

            grid.addComponent(new Label("Color"));
            ComboBox<String> colorBox = new ComboBox<>();
            colorBox.addItem(NO_COLOR);
            for (SessionColor c : SessionColor.values()) {
                colorBox.addItem(c.name());
            }
            colorBox.setSelectedItem(draft.color == null ? NO_COLOR : draft.color.name());
            grid.addComponent(colorBox);

            grid.addComponent(new Label("Tags (comma-sep)"));
            TextBox tagsBox = new TextBox(
                    new TerminalSize(40, 1),
                    String.join(", ", draft.tags));
            grid.addComponent(tagsBox);

            grid.addComponent(new Label("Pinned"));
            CheckBox pinBox = new CheckBox("Pin this session to the top");
            pinBox.setChecked(draft.pinned);
            grid.addComponent(pinBox);

            Panel buttons = new Panel();
            buttons.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            buttons.addComponent(new Button("Save", () -> {
                draft.title = blankToNull(titleBox.getText());
                draft.icon = blankToNull(iconBox.getText());
                String selectedColor = colorBox.getSelectedItem();
                draft.color = (selectedColor == null || NO_COLOR.equals(selectedColor))
                        ? null
                        : SessionColor.valueOf(selectedColor);
                draft.tags = normaliseTags(tagsBox.getText());
                draft.pinned = pinBox.isChecked();
                saved.set(true);
                window.close();
            }));
            buttons.addComponent(new Button("Cancel", window::close));

            Panel content = new Panel();
            content.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            content.addComponent(new Label(
                    "Edit metadata for session " + current.getSessionId()));
            content.addComponent(new Label(
                    "Tab to move, Enter on Save / Cancel, Esc to cancel."));
            content.addComponent(grid.withBorder(Borders.singleLine()));
            content.addComponent(buttons);
            window.setComponent(content);
            session.gui().addWindowAndWait(window);
        });
        return saved.get() ? draft : null;
    }

    private static List<String> normaliseTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : Arrays.asList(raw.split(","))) {
            String n = part.trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty()) out.add(n);
        }
        return new ArrayList<>(out);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nullToEmpty(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static boolean equalsNullable(@Nullable String a, @Nullable String b) {
        String aa = a == null ? "" : a;
        String bb = b == null ? "" : b;
        return aa.equals(bb);
    }

    private static boolean equalTags(List<String> a, List<String> b) {
        List<String> aa = a == null ? List.of() : a;
        List<String> bb = b == null ? List.of() : b;
        return aa.equals(bb);
    }

    /** Mutable draft passed through the Lanterna closure. */
    private static final class FormResult {
        @Nullable String title;
        @Nullable String icon;
        @Nullable SessionColor color;
        List<String> tags = new ArrayList<>();
        boolean pinned;
    }
}
