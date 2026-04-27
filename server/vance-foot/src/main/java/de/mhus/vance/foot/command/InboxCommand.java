package de.mhus.vance.foot.command;

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
import org.springframework.stereotype.Component;

/**
 * {@code /inbox [--all]} or {@code /inbox show <id>} — first
 * read-only iteration of the inbox client. Lists pending items by
 * default; {@code --all} includes archived/dismissed/answered.
 *
 * <p>Editing operations (answer / dismiss / archive / delegate) and
 * the rich Lanterna-based UI come in a follow-up etappe — this is
 * the plumbing-test for the WS read path.
 */
@Component
public class InboxCommand implements SlashCommand {

    private final ConnectionService connection;
    private final ChatTerminal terminal;

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
        return "Show your inbox. /inbox [--all] lists items, "
                + "/inbox show <id> shows one in detail.";
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
            default -> terminal.error("Usage: /inbox [--all] | /inbox show <id>");
        }
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
        terminal.info(String.format("%-12s %-10s %-12s %-7s %s",
                "ID-TAIL", "STATUS", "TYPE", "CRIT", "TITLE"));
        for (InboxItemDto item : response.getItems()) {
            terminal.info(String.format("%-12s %-10s %-12s %-7s %s",
                    tailId(item.getId()),
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
