package de.mhus.vance.foot.command;

import de.mhus.vance.api.inbox.AnswerOutcome;
import de.mhus.vance.api.inbox.InboxAnswerRequest;
import de.mhus.vance.api.inbox.InboxDelegateRequest;
import de.mhus.vance.api.inbox.InboxItemDto;
import de.mhus.vance.api.inbox.InboxItemIdRequest;
import de.mhus.vance.api.inbox.InboxItemStatus;
import de.mhus.vance.api.inbox.InboxListRequest;
import de.mhus.vance.api.inbox.InboxListResponse;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /inbox} — read and edit inbox items from the CLI without leaving
 * the JLine REPL. Subcommands:
 *
 * <ul>
 *   <li>{@code /inbox [--all]} — list (PENDING by default).</li>
 *   <li>{@code /inbox show <id>} — render one item in detail.</li>
 *   <li>{@code /inbox answer <id> [--outcome ...] [--text <words...> | --value <json>] [--reason ...]}
 *       — submit an answer. {@code --text} is shorthand for
 *       {@code --value {"text": "<words>"}}, which is the FEEDBACK
 *       convention.</li>
 *   <li>{@code /inbox archive <id>} / {@code /inbox dismiss <id>} —
 *       archive or dismiss without an answer.</li>
 *   <li>{@code /inbox delegate <id> <toUserId> [note...]} — forward.</li>
 * </ul>
 *
 * <p>The mutating subcommands mirror what {@code /ui-inbox} exposes via
 * buttons; both call the same WS endpoints. Argument tokens are split on
 * whitespace by {@link CommandService}, so {@code --reason} consumes
 * everything up to the next {@code --flag} or end of line — that lets
 * users write multi-word reasons without shell-style quoting.
 */
@Component
public class InboxCommand implements SlashCommand {

    private static final Duration WS_TIMEOUT = Duration.ofSeconds(10);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ConnectionService connection;
    private final ChatTerminal terminal;
    private final ObjectMapper json = JsonMapper.builder().build();

    public InboxCommand(ConnectionService connection, ChatTerminal terminal) {
        this.connection = connection;
        this.terminal = terminal;
    }

    @Override
    public String name() {
        return "inbox";
    }

    @Override
    public String description() {
        return "Inbox: list, show, answer, archive, dismiss, delegate. "
                + "/inbox help for syntax.";
    }

    @Override
    public void execute(List<String> args) throws Exception {
        if (args.isEmpty() || "--all".equals(args.get(0)) || "-a".equals(args.get(0))) {
            boolean all = !args.isEmpty();
            list(all);
            return;
        }
        String sub = args.get(0);
        List<String> rest = args.subList(1, args.size());
        switch (sub) {
            case "show" -> showOne(rest);
            case "answer" -> answer(rest);
            case "archive" -> archive(rest);
            case "dismiss" -> dismiss(rest);
            case "delegate" -> delegate(rest);
            case "help", "--help", "-h" -> printUsage();
            default -> {
                terminal.error("Unknown subcommand: " + sub);
                printUsage();
            }
        }
    }

    private void printUsage() {
        terminal.info("Usage:");
        terminal.info("  /inbox [--all]");
        terminal.info("  /inbox show <id>");
        terminal.info("  /inbox answer <id> [--outcome DECIDED|INSUFFICIENT_INFO|UNDECIDABLE]"
                + " [--text <words...> | --value <json>] [--reason <text...>]");
        terminal.info("  /inbox archive  <id>");
        terminal.info("  /inbox dismiss  <id>");
        terminal.info("  /inbox delegate <id> <toUserId> [note...]");
    }

    // ──────────────────── list ────────────────────

    private void list(boolean all) throws Exception {
        InboxListRequest req = InboxListRequest.builder()
                .status(all ? null : InboxItemStatus.PENDING)
                .build();
        InboxListResponse response = connection.request(
                MessageType.INBOX_LIST,
                req,
                InboxListResponse.class,
                Duration.ofSeconds(10));
        if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
            terminal.info(all
                    ? "Your inbox is empty."
                    : "No pending items. (use /inbox --all to see archived too)");
            return;
        }
        terminal.info(String.format("%-24s %-10s %-12s %-7s %s",
                "ID", "STATUS", "TYPE", "CRIT", "TITLE"));
        for (InboxItemDto item : response.getItems()) {
            terminal.info(String.format("%-24s %-10s %-12s %-7s %s",
                    Objects.toString(item.getId(), ""),
                    Objects.toString(item.getStatus(), ""),
                    Objects.toString(item.getType(), ""),
                    Objects.toString(item.getCriticality(), ""),
                    truncate(Objects.toString(item.getTitle(), ""), 60)));
        }
        terminal.info("→ " + response.getCount() + " item(s). /inbox show <id> for detail.");
    }

    // ──────────────────── show one ────────────────────

    private void showOne(List<String> rest) throws Exception {
        if (rest.isEmpty()) {
            terminal.error("Usage: /inbox show <id>");
            return;
        }
        String id = rest.get(0);
        InboxItemDto item = connection.request(
                MessageType.INBOX_ITEM,
                InboxItemIdRequest.builder().itemId(id).build(),
                InboxItemDto.class,
                Duration.ofSeconds(10));
        if (item == null) {
            terminal.error("Item not found: " + id);
            return;
        }
        renderItem(item);
    }

    private void renderItem(InboxItemDto item) {
        terminal.info("─── Inbox item ───");
        terminal.info("  id          " + item.getId());
        terminal.info("  type        " + item.getType()
                + (item.isRequiresAction() ? " (ask)" : " (output)"));
        terminal.info("  criticality " + item.getCriticality());
        terminal.info("  status      " + item.getStatus());
        terminal.info("  from        " + item.getOriginatorUserId());
        terminal.info("  to          " + item.getAssignedToUserId());
        if (item.getOriginProcessId() != null) {
            terminal.info("  process     " + item.getOriginProcessId());
        }
        if (item.getTags() != null && !item.getTags().isEmpty()) {
            terminal.info("  tags        " + String.join(", ", item.getTags()));
        }
        terminal.info("  title       " + Objects.toString(item.getTitle(), ""));
        if (item.getBody() != null && !item.getBody().isBlank()) {
            terminal.info("  body");
            for (String line : item.getBody().split("\n")) {
                terminal.info("    " + line);
            }
        }
        if (item.getPayload() != null && !item.getPayload().isEmpty()) {
            terminal.info("  payload");
            for (Map.Entry<String, Object> e : item.getPayload().entrySet()) {
                terminal.info("    " + e.getKey() + " = "
                        + truncate(String.valueOf(e.getValue()), 200));
            }
        }
        if (item.getAnswer() != null) {
            terminal.info("  answer      "
                    + Objects.toString(item.getAnswer().getOutcome(), "")
                    + (item.getAnswer().getValue() == null
                            ? ""
                            : "  value=" + truncate(
                                    String.valueOf(item.getAnswer().getValue()), 100))
                    + (item.getAnswer().getReason() == null
                            ? ""
                            : "  reason=" + truncate(item.getAnswer().getReason(), 100))
                    + "  by " + Objects.toString(item.getAnswer().getAnsweredBy(), ""));
        }
        if (item.getResolvedBy() != null) {
            terminal.info("  resolvedBy  " + item.getResolvedBy()
                    + (item.getResolvedAt() == null ? "" : " at " + item.getResolvedAt()));
        }
    }

    // ──────────────────── answer ────────────────────

    private void answer(List<String> rest) throws Exception {
        if (rest.isEmpty()) {
            terminal.error("Usage: /inbox answer <id> [--outcome DECIDED|INSUFFICIENT_INFO|UNDECIDABLE]"
                    + " [--text <words...> | --value <json>] [--reason <text...>]");
            return;
        }
        String id = rest.get(0);
        AnswerOutcome outcome = AnswerOutcome.DECIDED;
        @Nullable String reason = null;
        @Nullable String valueJson = null;
        int i = 1;
        while (i < rest.size()) {
            String arg = rest.get(i);
            switch (arg) {
                case "--outcome" -> {
                    if (i + 1 >= rest.size()) {
                        terminal.error("--outcome requires a value");
                        return;
                    }
                    String token = rest.get(i + 1);
                    try {
                        outcome = AnswerOutcome.valueOf(token.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        terminal.error("Unknown outcome: " + token
                                + " — use DECIDED, INSUFFICIENT_INFO, or UNDECIDABLE.");
                        return;
                    }
                    i += 2;
                }
                case "--value" -> {
                    if (valueJson != null) {
                        terminal.error("--text and --value are mutually exclusive");
                        return;
                    }
                    int start = i + 1;
                    int end = start;
                    while (end < rest.size() && !rest.get(end).startsWith("--")) {
                        end++;
                    }
                    if (start >= end) {
                        terminal.error("--value requires a JSON object");
                        return;
                    }
                    // JSON is whitespace-tolerant, so re-joining the tokens
                    // with spaces gives the same parse as if the line had
                    // arrived intact. Lets users write {"text": "Café in Berlin"}.
                    valueJson = String.join(" ", rest.subList(start, end));
                    i = end;
                }
                case "--text" -> {
                    if (valueJson != null) {
                        terminal.error("--text and --value are mutually exclusive");
                        return;
                    }
                    int start = i + 1;
                    int end = start;
                    while (end < rest.size() && !rest.get(end).startsWith("--")) {
                        end++;
                    }
                    if (start >= end) {
                        terminal.error("--text requires a value");
                        return;
                    }
                    String text = String.join(" ", rest.subList(start, end));
                    // Build the JSON via Jackson so embedded quotes,
                    // backslashes and non-ASCII bytes get escaped correctly
                    // — much safer than concatenating "{\"text\":\"" + raw.
                    valueJson = json.writeValueAsString(Map.of("text", text));
                    i = end;
                }
                case "--reason" -> {
                    int start = i + 1;
                    int end = start;
                    while (end < rest.size() && !rest.get(end).startsWith("--")) {
                        end++;
                    }
                    if (start >= end) {
                        terminal.error("--reason requires a text");
                        return;
                    }
                    reason = String.join(" ", rest.subList(start, end));
                    i = end;
                }
                default -> {
                    terminal.error("Unknown flag: " + arg);
                    return;
                }
            }
        }

        @Nullable Map<String, Object> value = null;
        if (valueJson != null) {
            try {
                value = json.readValue(valueJson, MAP_TYPE);
            } catch (Exception e) {
                terminal.error("--value is not a valid JSON object: " + e.getMessage());
                return;
            }
        }
        if (outcome == AnswerOutcome.DECIDED && value == null) {
            terminal.error("--outcome DECIDED requires --value <json-object>");
            return;
        }
        if ((outcome == AnswerOutcome.INSUFFICIENT_INFO
                || outcome == AnswerOutcome.UNDECIDABLE)
                && (reason == null || reason.isBlank())) {
            terminal.error("--outcome " + outcome + " requires --reason <text>");
            return;
        }

        InboxAnswerRequest req = InboxAnswerRequest.builder()
                .itemId(id)
                .outcome(outcome)
                .value(value)
                .reason(reason)
                .build();
        connection.request(MessageType.INBOX_ANSWER, req, Void.class, WS_TIMEOUT);
        terminal.info("Answered " + tailId(id) + " (" + outcome + ").");
    }

    // ──────────────────── archive / dismiss / delegate ────────────────────

    private void archive(List<String> rest) throws Exception {
        if (rest.size() != 1) {
            terminal.error("Usage: /inbox archive <id>");
            return;
        }
        String id = rest.get(0);
        connection.request(MessageType.INBOX_ARCHIVE,
                InboxItemIdRequest.builder().itemId(id).build(),
                Void.class, WS_TIMEOUT);
        terminal.info("Archived " + tailId(id) + ".");
    }

    private void dismiss(List<String> rest) throws Exception {
        if (rest.size() != 1) {
            terminal.error("Usage: /inbox dismiss <id>");
            return;
        }
        String id = rest.get(0);
        connection.request(MessageType.INBOX_DISMISS,
                InboxItemIdRequest.builder().itemId(id).build(),
                Void.class, WS_TIMEOUT);
        terminal.info("Dismissed " + tailId(id) + ".");
    }

    private void delegate(List<String> rest) throws Exception {
        if (rest.size() < 2) {
            terminal.error("Usage: /inbox delegate <id> <toUserId> [note...]");
            return;
        }
        String id = rest.get(0);
        String toUserId = rest.get(1);
        @Nullable String note = rest.size() > 2
                ? String.join(" ", rest.subList(2, rest.size()))
                : null;
        connection.request(MessageType.INBOX_DELEGATE,
                InboxDelegateRequest.builder()
                        .itemId(id)
                        .toUserId(toUserId)
                        .note(note)
                        .build(),
                Void.class, WS_TIMEOUT);
        terminal.info("Delegated " + tailId(id) + " → " + toUserId + ".");
    }

    // ──────────────────── helpers ────────────────────

    private static String tailId(String id) {
        if (id == null) return "(none)";
        return id.length() <= 12 ? id : "…" + id.substring(id.length() - 11);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
