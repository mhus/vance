package de.mhus.vance.foot.command;

import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Border;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import de.mhus.vance.api.chat.ChatMessageDto;
import de.mhus.vance.api.thinkprocess.ProcessListRequest;
import de.mhus.vance.api.thinkprocess.ProcessListResponse;
import de.mhus.vance.api.thinkprocess.ProcessMessagesRequest;
import de.mhus.vance.api.thinkprocess.ProcessMessagesResponse;
import de.mhus.vance.api.thinkprocess.ProcessPauseRequest;
import de.mhus.vance.api.thinkprocess.ProcessResumeRequest;
import de.mhus.vance.api.thinkprocess.ProcessSteerRequest;
import de.mhus.vance.api.thinkprocess.ProcessStopRequest;
import de.mhus.vance.api.thinkprocess.ProcessSummary;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /ui-process} — fullscreen Lanterna process manager: which
 * think-processes the session is running, what each one is doing, and the
 * controls to steer them. The visual counterpart of the line-based
 * {@link ProcessListCommand} / {@link ProcessSteerCommand} / {@code /process}
 * trio.
 *
 * <p>Master list shows one row per process (status, engine, goal). Enter
 * opens the detail window with the process's own conversation — pulled via
 * {@code process-messages}, which is session-scoped by construction, so this
 * view can never wander into another session's transcript
 * ({@code planning/process-visibility.md} §5.1).
 *
 * <p>Detail actions: <b>Activate</b> makes the process the target of plain
 * chat input (same pointer {@code /process <name>} sets), <b>Steer</b> sends
 * one message, plus Pause / Resume / Stop. Steering a process whose lane is
 * currently driven by an orchestrator is allowed — the server queues it —
 * and the confirmation says so rather than pretending it took effect
 * (§5.2).
 *
 * <p>Requirement: planning/process-visibility.md §4.B
 */
@Component
public class UiProcessCommand implements SlashCommand {

    private static final Duration WS_TIMEOUT = Duration.ofSeconds(10);

    /** Newest-N turns pulled per detail open. */
    private static final int HISTORY_LIMIT = 100;

    private final ConnectionService connection;
    private final SessionService sessions;
    private final ChatTerminal terminal;
    private final InterfaceService ui;

    public UiProcessCommand(ConnectionService connection,
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
        return "ui-process";
    }

    @Override
    public String description() {
        return "Open the session's think-processes in a fullscreen UI.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (sessions.current() == null) {
            terminal.error("No session bound — /session-bootstrap first.");
            return;
        }
        ui.runFullscreen(session -> {
            View view = new View(session.gui());
            view.refresh();
            session.gui().addWindowAndWait(view.window);
        });
    }

    /** Master list of the session's processes. */
    private final class View {

        private final WindowBasedTextGUI gui;
        private final BasicWindow window;
        private final Label header = new Label("");
        private final ActionListBox listBox = new ActionListBox();
        private final Button filterButton;
        private boolean showTerminated = false;
        private List<ProcessSummary> rows = List.of();

        View(WindowBasedTextGUI gui) {
            this.gui = gui;
            this.window = new BasicWindow("Processes");
            window.setHints(Set.of(Window.Hint.FULL_SCREEN));
            window.setCloseWindowWithEscape(true);

            this.filterButton = new Button(filterButtonLabel(), this::toggleFilter);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            root.addComponent(header);

            Border listBorder = listBox.withBorder(Borders.singleLine("Processes"));
            listBorder.setLayoutData(LinearLayout.createLayoutData(
                    LinearLayout.Alignment.Fill,
                    LinearLayout.GrowPolicy.CanGrow));
            root.addComponent(listBorder);

            Panel actions = new Panel();
            actions.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            actions.addComponent(new Button("Refresh", this::refresh));
            actions.addComponent(filterButton);
            actions.addComponent(new Button("Quit", window::close));
            root.addComponent(actions);

            window.setComponent(root);
        }

        void toggleFilter() {
            showTerminated = !showTerminated;
            filterButton.setLabel(filterButtonLabel());
            refresh();
        }

        void refresh() {
            try {
                ProcessListResponse response = connection.request(
                        MessageType.PROCESS_LIST,
                        ProcessListRequest.builder()
                                .includeTerminated(showTerminated)
                                .build(),
                        ProcessListResponse.class,
                        WS_TIMEOUT);
                rows = response == null || response.getProcesses() == null
                        ? List.of()
                        : response.getProcesses();
            } catch (Exception e) {
                rows = List.of();
                showError("Load failed", e.getMessage());
            }
            rebuildList();
        }

        private void rebuildList() {
            listBox.clearItems();
            if (rows.isEmpty()) {
                header.setText(showTerminated
                        ? "No processes in this session."
                        : "No live processes. Press [Filter] to include closed ones.");
                return;
            }
            String active = sessions.activeProcess();
            header.setText(rows.size() + " process" + (rows.size() == 1 ? "" : "es")
                    + " — Enter to open"
                    + (active == null ? "" : ", active: " + active)
                    + (showTerminated ? ", incl. closed." : "."));
            for (ProcessSummary row : rows) {
                listBox.addItem(formatRow(row), () -> openDetail(row));
            }
        }

        private String filterButtonLabel() {
            return showTerminated ? "Filter: ALL" : "Filter: LIVE";
        }

        private void openDetail(ProcessSummary row) {
            Detail detail = new Detail(gui, row);
            detail.load();
            gui.addWindowAndWait(detail.window);
            if (detail.mutated) {
                refresh();
            } else {
                // Activation doesn't change the server state but does change
                // the header line.
                rebuildList();
            }
        }

        private void showError(String title, @Nullable String message) {
            UiProcessCommand.showError(gui, title, message);
        }
    }

    /** One process: metadata, its conversation, and the controls. */
    private final class Detail {

        private final WindowBasedTextGUI gui;
        private final ProcessSummary row;
        private final BasicWindow window;
        private final Label statusLabel = new Label("");
        private final TextBox transcript;
        private boolean mutated = false;

        Detail(WindowBasedTextGUI gui, ProcessSummary row) {
            this.gui = gui;
            this.row = row;
            this.window = new BasicWindow("Process — " + row.getName());
            window.setHints(Set.of(Window.Hint.FULL_SCREEN));
            window.setCloseWindowWithEscape(true);

            this.transcript = new TextBox("", TextBox.Style.MULTI_LINE);
            transcript.setReadOnly(true);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            root.addComponent(statusLabel);
            if (row.getGoal() != null && !row.getGoal().isBlank()) {
                root.addComponent(new Label("Goal: " + oneLine(row.getGoal(), 200)));
            }

            Border box = transcript.withBorder(Borders.singleLine("Conversation"));
            box.setLayoutData(LinearLayout.createLayoutData(
                    LinearLayout.Alignment.Fill,
                    LinearLayout.GrowPolicy.CanGrow));
            root.addComponent(box);

            Panel actions = new Panel();
            actions.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            actions.addComponent(new Button("Activate", this::activate));
            actions.addComponent(new Button("Steer", this::steer));
            actions.addComponent(new Button("Pause", () -> lifecycle(
                    MessageType.PROCESS_PAUSE,
                    ProcessPauseRequest.builder().processName(row.getName()).build(),
                    "paused")));
            actions.addComponent(new Button("Resume", () -> lifecycle(
                    MessageType.PROCESS_RESUME,
                    ProcessResumeRequest.builder().processName(row.getName()).build(),
                    "resumed")));
            actions.addComponent(new Button("Stop", () -> lifecycle(
                    MessageType.PROCESS_STOP,
                    ProcessStopRequest.builder().processName(row.getName()).build(),
                    "stopped")));
            actions.addComponent(new Button("Reload", this::load));
            actions.addComponent(new Button("Close", window::close));
            root.addComponent(actions);

            window.setComponent(root);
        }

        /** Pull the process's own conversation and render it read-only. */
        void load() {
            ProcessMessagesResponse response;
            try {
                response = connection.request(
                        MessageType.PROCESS_MESSAGES,
                        ProcessMessagesRequest.builder()
                                .name(row.getName())
                                .limit(HISTORY_LIMIT)
                                .build(),
                        ProcessMessagesResponse.class,
                        WS_TIMEOUT);
            } catch (Exception e) {
                statusLabel.setText(headerLine(row.getStatus()));
                transcript.setText("(could not load conversation: " + e.getMessage() + ")");
                return;
            }
            if (response == null) {
                statusLabel.setText(headerLine(row.getStatus()));
                transcript.setText("(no reply)");
                return;
            }
            statusLabel.setText(headerLine(response.getStatus()));
            transcript.setText(renderTranscript(response));
        }

        private String headerLine(@Nullable ThinkProcessStatus status) {
            StringBuilder b = new StringBuilder();
            b.append("engine=").append(Objects.toString(row.getThinkEngine(), "?"));
            b.append("  status=").append(status == null ? "?" : status.name().toLowerCase());
            if (row.getCloseReason() != null) {
                b.append(" (").append(row.getCloseReason().name().toLowerCase()).append(')');
            }
            String active = sessions.activeProcess();
            if (row.getName().equals(active)) {
                b.append("  [active for chat input]");
            }
            return b.toString();
        }

        private String renderTranscript(ProcessMessagesResponse response) {
            List<ChatMessageDto> messages = response.getMessages() == null
                    ? List.of() : response.getMessages();
            if (messages.isEmpty()) {
                return "(no messages yet — the process hasn't spoken)";
            }
            StringBuilder b = new StringBuilder();
            if (response.getOlderTruncated() != null) {
                b.append("… ").append(response.getOlderTruncated())
                        .append(" older message(s) not shown\n\n");
            }
            for (ChatMessageDto m : messages) {
                b.append('[').append(String.valueOf(m.getRole()).toLowerCase()).append("] ");
                b.append(Objects.toString(m.getContent(), "")).append("\n\n");
            }
            return b.toString();
        }

        /**
         * Point plain chat input at this process — the same pointer
         * {@code /process <name>} sets, so the two surfaces agree.
         */
        private void activate() {
            sessions.setActiveProcess(row.getName());
            statusLabel.setText(headerLine(row.getStatus()));
            info("Active process", "Plain chat input now goes to '" + row.getName()
                    + "'. Use /process - to clear.");
        }

        private void steer() {
            String text = TextInputDialog.showDialog(
                    gui, "Steer " + row.getName(), "Message to the process:", "");
            if (text == null || text.isBlank()) {
                return;
            }
            try {
                connection.request(
                        MessageType.PROCESS_STEER,
                        ProcessSteerRequest.builder()
                                .processName(row.getName())
                                .content(text)
                                .build(),
                        Object.class,
                        WS_TIMEOUT);
                mutated = true;
                // Honest about the lane: a busy process takes the message but
                // acts on it only after its current turn (§5.2).
                boolean busy = row.getStatus() == ThinkProcessStatus.RUNNING;
                info("Sent", busy
                        ? "Queued — the process is working and will pick it up "
                          + "after its current turn."
                        : "Delivered to '" + row.getName() + "'.");
                load();
            } catch (Exception e) {
                UiProcessCommand.showError(gui, "Steer failed", e.getMessage());
            }
        }

        private void lifecycle(String type, Object payload, String pastTense) {
            try {
                connection.request(type, payload, Object.class, WS_TIMEOUT);
                mutated = true;
                info("Done", "Process '" + row.getName() + "' " + pastTense + ".");
                load();
            } catch (Exception e) {
                UiProcessCommand.showError(gui, "Command failed", e.getMessage());
            }
        }

        private void info(String title, String message) {
            new MessageDialogBuilder()
                    .setTitle(title)
                    .setText(message)
                    .addButton(MessageDialogButton.OK)
                    .build()
                    .showDialog(gui);
        }
    }

    // ── formatting helpers ───────────────────────────────────────────────

    static String formatRow(ProcessSummary row) {
        StringBuilder b = new StringBuilder();
        b.append(pad(row.getStatus() == null ? "?" : row.getStatus().name().toLowerCase(), 10));
        b.append(pad(row.getName(), 22));
        b.append(pad(Objects.toString(row.getThinkEngine(), "?"), 14));
        String goal = row.getGoal();
        if (goal != null && !goal.isBlank()) {
            b.append(oneLine(goal, 60));
        }
        return b.toString();
    }

    private static String pad(@Nullable String value, int width) {
        String s = value == null ? "" : value;
        if (s.length() >= width) {
            return s.substring(0, Math.max(0, width - 1)) + " ";
        }
        return s + " ".repeat(width - s.length());
    }

    /** Collapse newlines and clamp — list rows must stay single-line. */
    static String oneLine(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static void showError(
            WindowBasedTextGUI gui, String title, @Nullable String message) {
        new MessageDialogBuilder()
                .setTitle(title)
                .setText(message == null ? "(no message)" : message)
                .addButton(MessageDialogButton.OK)
                .build()
                .showDialog(gui);
    }
}
