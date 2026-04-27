package de.mhus.vance.foot.command;

import com.googlecode.lanterna.TerminalSize;
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
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.TextInputDialog;
import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.InboxAnswerRequest;
import de.mhus.vance.api.inbox.InboxDelegateRequest;
import de.mhus.vance.api.inbox.InboxItemDto;
import de.mhus.vance.api.inbox.InboxItemIdRequest;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxItemType;
import de.mhus.vance.api.inbox.InboxListRequest;
import de.mhus.vance.api.inbox.InboxListResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.InterfaceService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code /ui-inbox} — fullscreen Lanterna inbox manager, the visual
 * counterpart of the read-only {@link InboxCommand}. Layout is
 * mail-client-style: a master list of items in the main window, a detail
 * dialog with action buttons (archive / dismiss / delegate) on click. The
 * list refreshes after every successful action.
 *
 * <p>Answering follows the same type-specific shape Marvin reads back as
 * {@code artifacts.userAnswer}: {@code FEEDBACK} / {@code OUTPUT_TEXT}
 * collect a single line into {@code {"text": …}}, {@code DECISION}
 * collects a Yes/No into {@code {"decision": …}}, {@code APPROVAL} into
 * {@code {"approved": …}}. Other types fall back to a hint that points at
 * {@code /inbox answer --value <json>}.
 */
@Component
public class UiInboxCommand implements SlashCommand {

    private static final Duration WS_TIMEOUT = Duration.ofSeconds(10);

    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final InterfaceService ui;

    public UiInboxCommand(ConnectionService connection,
                          ChatTerminal terminal,
                          InterfaceService ui) {
        this.connection = connection;
        this.terminal = terminal;
        this.ui = ui;
    }

    @Override
    public String name() {
        return "ui-inbox";
    }

    @Override
    public String description() {
        return "Open the inbox in a fullscreen mail-style UI.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        ui.runFullscreen(session -> {
            View view = new View(session.gui());
            view.refresh();
            session.gui().addWindowAndWait(view.window);
        });
    }

    /**
     * Mail-client-style master view. Holds the list-box, the filter state
     * and the load logic; rebuilt in-place on every refresh so item ids
     * stay consistent with what the user clicks.
     */
    private final class View {

        private final WindowBasedTextGUI gui;
        private final BasicWindow window;
        private final Label header = new Label("");
        private final ActionListBox listBox = new ActionListBox();
        private final Button filterButton;
        private boolean showAll = false;
        private List<InboxItemDto> items = List.of();

        View(WindowBasedTextGUI gui) {
            this.gui = gui;
            this.window = new BasicWindow("Inbox");
            window.setHints(Set.of(Window.Hint.FULL_SCREEN));
            window.setCloseWindowWithEscape(true);

            this.filterButton = new Button(filterButtonLabel(), this::toggleFilter);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
            root.addComponent(header);

            // Make the list-box+border eat the remaining vertical space and
            // the full window width: Fill stretches across the layout's
            // cross-axis (width in a VERTICAL layout), CanGrow lets the
            // component absorb leftover rows on the main axis.
            Border listBorder = listBox.withBorder(Borders.singleLine("Items"));
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
            showAll = !showAll;
            filterButton.setLabel(filterButtonLabel());
            refresh();
        }

        void refresh() {
            try {
                InboxListResponse response = connection.request(
                        MessageType.INBOX_LIST,
                        InboxListRequest.builder()
                                .status(showAll ? null : InboxItemStatus.PENDING)
                                .build(),
                        InboxListResponse.class,
                        WS_TIMEOUT);
                items = response == null || response.getItems() == null
                        ? List.of()
                        : response.getItems();
            } catch (Exception e) {
                items = List.of();
                showError("Load failed", e.getMessage());
            }
            rebuildList();
        }

        private void rebuildList() {
            listBox.clearItems();
            if (items.isEmpty()) {
                header.setText(showAll
                        ? "Inbox is empty."
                        : "No pending items. Press [Filter] to show archived/answered.");
                return;
            }
            header.setText(items.size() + " item" + (items.size() == 1 ? "" : "s")
                    + " — Enter to open, " + (showAll ? "showing all." : "pending only."));
            for (InboxItemDto item : items) {
                listBox.addItem(formatRow(item), () -> openDetail(item));
            }
        }

        private String filterButtonLabel() {
            return showAll ? "Filter: ALL" : "Filter: PENDING";
        }

        private void openDetail(InboxItemDto item) {
            Detail detail = new Detail(item);
            gui.addWindowAndWait(detail.window);
            if (detail.mutated) {
                refresh();
            }
        }

        private void showError(String title, @Nullable String message) {
            new MessageDialogBuilder()
                    .setTitle(title)
                    .setText(message == null ? "(no message)" : message)
                    .addButton(MessageDialogButton.OK)
                    .build()
                    .showDialog(gui);
        }
    }

    /**
     * Per-item detail window. Renders header / body / payload read-only and
     * exposes Archive / Dismiss / Delegate buttons. Sets {@code mutated}
     * before closing so the master view knows to reload.
     */
    private final class Detail {

        private final InboxItemDto item;
        private final BasicWindow window;
        private boolean mutated = false;

        Detail(InboxItemDto item) {
            this.item = item;
            this.window = new BasicWindow("Inbox item — " + tailId(item.getId()));
            window.setHints(Set.of(Window.Hint.CENTERED, Window.Hint.FIT_TERMINAL_WINDOW));
            window.setCloseWindowWithEscape(true);

            Panel root = new Panel();
            root.setLayoutManager(new LinearLayout(Direction.VERTICAL));

            root.addComponent(new Label(headerLine()));
            root.addComponent(new Label("From: " + Objects.toString(item.getOriginatorUserId(), "")
                    + "   To: " + Objects.toString(item.getAssignedToUserId(), "")));
            if (item.getOriginProcessId() != null) {
                root.addComponent(new Label("Process: " + item.getOriginProcessId()));
            }
            if (item.getTags() != null && !item.getTags().isEmpty()) {
                root.addComponent(new Label("Tags: " + String.join(", ", item.getTags())));
            }
            root.addComponent(new Label("Title: " + Objects.toString(item.getTitle(), "")));

            String bodyText = renderBody();
            if (!bodyText.isEmpty()) {
                TextBox body = new TextBox(new TerminalSize(80, 12), bodyText, TextBox.Style.MULTI_LINE);
                body.setReadOnly(true);
                root.addComponent(body.withBorder(Borders.singleLine("Body / payload")));
            }

            if (item.getAnswer() != null) {
                root.addComponent(new Label("Answer: "
                        + Objects.toString(item.getAnswer().getOutcome(), "")
                        + (item.getAnswer().getReason() == null
                                ? ""
                                : "  reason=" + item.getAnswer().getReason())));
            }

            Panel buttons = new Panel();
            buttons.setLayoutManager(new LinearLayout(Direction.HORIZONTAL));
            boolean editable = item.getStatus() == InboxItemStatus.PENDING;
            boolean canAnswer = editable && item.isRequiresAction();
            if (canAnswer) {
                buttons.addComponent(new Button("Answer…", this::answer));
                buttons.addComponent(new Button("Insuff. info…",
                        () -> declineWithReason(AnswerOutcome.INSUFFICIENT_INFO)));
                buttons.addComponent(new Button("Undecidable…",
                        () -> declineWithReason(AnswerOutcome.UNDECIDABLE)));
            }
            if (editable) {
                buttons.addComponent(new Button("Archive", this::archive));
                buttons.addComponent(new Button("Dismiss", this::dismiss));
                buttons.addComponent(new Button("Delegate…", this::delegate));
            } else {
                buttons.addComponent(new Label("(read-only — status " + item.getStatus() + ")"));
            }
            buttons.addComponent(new Button("Close", window::close));
            root.addComponent(buttons);

            window.setComponent(root);
        }

        private String headerLine() {
            return "Type: " + Objects.toString(item.getType(), "")
                    + (item.isRequiresAction() ? " (ask)" : " (output)")
                    + "   Criticality: " + Objects.toString(item.getCriticality(), "")
                    + "   Status: " + Objects.toString(item.getStatus(), "");
        }

        private String renderBody() {
            StringBuilder sb = new StringBuilder();
            if (item.getBody() != null && !item.getBody().isBlank()) {
                sb.append(item.getBody());
            }
            if (item.getPayload() != null && !item.getPayload().isEmpty()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append("payload:\n");
                for (Map.Entry<String, Object> e : item.getPayload().entrySet()) {
                    sb.append("  ").append(e.getKey()).append(" = ")
                      .append(String.valueOf(e.getValue())).append('\n');
                }
            }
            return sb.toString();
        }

        private void answer() {
            Map<String, Object> value = collectAnswerValue(item.getType());
            if (value == null) return;
            InboxAnswerRequest req = InboxAnswerRequest.builder()
                    .itemId(item.getId())
                    .outcome(AnswerOutcome.DECIDED)
                    .value(value)
                    .build();
            performAction("Answer",
                    () -> connection.request(MessageType.INBOX_ANSWER, req,
                            Void.class, WS_TIMEOUT));
        }

        /**
         * Type-specific answer-value collector. The keys mirror what the
         * {@code /inbox} CLI's {@code --text} shorthand produces and what
         * Marvin's worker prompts look for inside {@code userAnswer}.
         * Returns {@code null} when the user cancels or the type has no UI
         * collector yet.
         */
        private @Nullable Map<String, Object> collectAnswerValue(InboxItemType type) {
            WindowBasedTextGUI gui = window.getTextGUI();
            switch (type) {
                case FEEDBACK, OUTPUT_TEXT -> {
                    String text = TextInputDialog.showDialog(gui,
                            "Answer", "Your reply:", "");
                    if (text == null || text.isBlank()) return null;
                    return Map.of("text", text);
                }
                case DECISION -> {
                    MessageDialogButton choice = new MessageDialogBuilder()
                            .setTitle("Decision")
                            .setText("Decide:")
                            .addButton(MessageDialogButton.Yes)
                            .addButton(MessageDialogButton.No)
                            .addButton(MessageDialogButton.Cancel)
                            .build()
                            .showDialog(gui);
                    if (choice == MessageDialogButton.Yes) return Map.of("decision", true);
                    if (choice == MessageDialogButton.No) return Map.of("decision", false);
                    return null;
                }
                case APPROVAL -> {
                    MessageDialogButton choice = new MessageDialogBuilder()
                            .setTitle("Approval")
                            .setText("Approve?")
                            .addButton(MessageDialogButton.Yes)
                            .addButton(MessageDialogButton.No)
                            .addButton(MessageDialogButton.Cancel)
                            .build()
                            .showDialog(gui);
                    if (choice == MessageDialogButton.Yes) return Map.of("approved", true);
                    if (choice == MessageDialogButton.No) return Map.of("approved", false);
                    return null;
                }
                default -> {
                    MessageDialog.showMessageDialog(gui,
                            "Not supported in UI",
                            "Type " + type + " has no UI form yet — use\n"
                                    + "/inbox answer " + item.getId() + " --value <json>",
                            MessageDialogButton.OK);
                    return null;
                }
            }
        }

        private void declineWithReason(AnswerOutcome outcome) {
            String reason = TextInputDialog.showDialog(window.getTextGUI(),
                    outcome == AnswerOutcome.INSUFFICIENT_INFO ? "Insufficient info" : "Undecidable",
                    "Reason (Esc / empty cancels):",
                    "");
            if (reason == null || reason.isBlank()) return;
            InboxAnswerRequest req = InboxAnswerRequest.builder()
                    .itemId(item.getId())
                    .outcome(outcome)
                    .reason(reason)
                    .build();
            performAction(outcome.name(),
                    () -> connection.request(MessageType.INBOX_ANSWER, req,
                            Void.class, WS_TIMEOUT));
        }

        private void archive() {
            if (!confirm("Archive this item?")) return;
            performAction("Archive",
                    () -> connection.request(
                            MessageType.INBOX_ARCHIVE,
                            InboxItemIdRequest.builder().itemId(item.getId()).build(),
                            Void.class,
                            WS_TIMEOUT));
        }

        private void dismiss() {
            if (!confirm("Dismiss this item? The originating process will treat it as a skip.")) return;
            performAction("Dismiss",
                    () -> connection.request(
                            MessageType.INBOX_DISMISS,
                            InboxItemIdRequest.builder().itemId(item.getId()).build(),
                            Void.class,
                            WS_TIMEOUT));
        }

        private void delegate() {
            String to = TextInputDialog.showDialog(window.getTextGUI(),
                    "Delegate item",
                    "Forward this item to which user-id?",
                    "");
            if (to == null || to.isBlank()) return;
            String note = TextInputDialog.showDialog(window.getTextGUI(),
                    "Delegate item",
                    "Optional note (Esc / empty = none):",
                    "");
            String trimmed = to.trim();
            performAction("Delegate",
                    () -> connection.request(
                            MessageType.INBOX_DELEGATE,
                            InboxDelegateRequest.builder()
                                    .itemId(item.getId())
                                    .toUserId(trimmed)
                                    .note(note == null || note.isBlank() ? null : note)
                                    .build(),
                            Void.class,
                            WS_TIMEOUT));
        }

        private boolean confirm(String message) {
            MessageDialogButton choice = new MessageDialogBuilder()
                    .setTitle("Confirm")
                    .setText(message)
                    .addButton(MessageDialogButton.Yes)
                    .addButton(MessageDialogButton.No)
                    .build()
                    .showDialog(window.getTextGUI());
            return choice == MessageDialogButton.Yes;
        }

        private void performAction(String label, ActionCall call) {
            try {
                call.run();
                mutated = true;
                window.close();
                terminal.info(label + " ok: " + tailId(item.getId()));
            } catch (Exception e) {
                MessageDialog.showMessageDialog(window.getTextGUI(),
                        label + " failed",
                        e.getMessage() == null ? e.toString() : e.getMessage(),
                        MessageDialogButton.OK);
            }
        }
    }

    @FunctionalInterface
    private interface ActionCall {
        void run() throws Exception;
    }

    private static String formatRow(InboxItemDto item) {
        return String.format("%-12s  %-9s  %-12s  %-7s  %s",
                tailId(item.getId()),
                Objects.toString(item.getStatus(), ""),
                Objects.toString(item.getType(), ""),
                Objects.toString(item.getCriticality(), ""),
                truncate(Objects.toString(item.getTitle(), ""), 60));
    }

    private static String tailId(@Nullable String id) {
        if (id == null) return "(none)";
        return id.length() <= 12 ? id : "…" + id.substring(id.length() - 11);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
