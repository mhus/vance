package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What is waiting on the caller. The entry point of both families: every other
 * tool needs a {@code threadId}, and this is the only place one comes from.
 *
 * <p>Answers "how many" and "which" in one call, which is why there is no
 * separate count tool.
 *
 * <p>No {@code assignedTo} parameter. The REST grammar knows {@code team:<name>}
 * but a team view is looking on — it costs tokens and re-opens the question
 * "whose inbox is the agent reading" for every model, every turn. v1: the owner,
 * always.
 */
@Component
@RequiredArgsConstructor
public class InboxListTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "status", Map.of(
                            "type", "string",
                            "enum", List.of("PENDING", "ANSWERED", "DISMISSED", "ARCHIVED"),
                            "description", "Filter by state. Omit for everything "
                                    + "except ARCHIVED."),
                    "onlyUnread", Map.of(
                            "type", "boolean",
                            "description", "Only threads with something you have not read."),
                    "onlyAsks", Map.of(
                            "type", "boolean",
                            "description", "Only threads that are waiting for an answer "
                                    + "from a person."),
                    "tag", Map.of(
                            "type", "string",
                            "description", "Single tag filter."),
                    "limit", Map.of(
                            "type", "integer",
                            "description", "Newest first. Default 20, capped at 50.")),
            "required", List.of());

    private final MaximegalonService threads;
    private final InboxToolSupport support;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    @Override public String name() { return "inbox_list"; }

    @Override public String description() {
        return "List the inbox threads waiting on you — one line each, newest first, "
                + "plus your unread counts. This is your Vance inbox: matters other people "
                + "and agents put in front of you, each heading for at most one decision. "
                + "Not email, and not a chat. Use `thread_get` to read one, and note that "
                + "reading never answers anything.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("read-only"); }

    @Override public String searchHint() {
        return "See what is waiting on you in your Vance inbox (requests, decisions, results)";
    }

    @Override public String troubleshootingHint() {
        return "Thread ids come from inbox_list. Nothing in the inbox or thread tools "
                + "answers an ask — that is a person's decision.";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = support.tenantOrThrow(ctx);
        String owner = support.ownerOrThrow(ctx);
        // Same gate the REST list uses. The target user is hard-wired to the
        // owner, so there is nothing here a caller could point elsewhere.
        permissionService.enforce(
                contextFactory.forToolSubject(tenantId, owner),
                new Resource.Tenant(tenantId), Action.READ);

        MaximegalonStatus status = parseStatus(params.get("status"));
        boolean onlyUnread = boolParam(params, "onlyUnread");
        boolean onlyAsks = boolParam(params, "onlyAsks");
        String tag = stringParam(params, "tag");
        int requested = intParam(params, "limit", DEFAULT_LIMIT);
        // Clamped rather than refused: a rejection would cost a turn for
        // something we can simply do, and `truncated` says what happened.
        int limit = Math.min(Math.max(1, requested), MAX_LIMIT);

        List<MaximegalonDocument> all =
                threads.listFiltered(tenantId, List.of(owner), status, tag);
        List<MaximegalonDocument> matching = new ArrayList<>(all.size());
        for (MaximegalonDocument doc : all) {
            if (status == null && doc.getStatus() == MaximegalonStatus.ARCHIVED) continue;
            if (onlyAsks && !doc.isRequiresAction()) continue;
            if (onlyUnread && !isUnread(doc, owner)) continue;
            matching.add(doc);
        }
        boolean truncated = matching.size() > limit;
        List<MaximegalonDocument> page = truncated ? matching.subList(0, limit) : matching;

        Map<String, Integer> messageCounts = threads.countMessages(tenantId,
                page.stream().map(MaximegalonDocument::getId)
                        .filter(java.util.Objects::nonNull).toList());

        List<Map<String, Object>> rows = new ArrayList<>(page.size());
        for (MaximegalonDocument doc : page) {
            rows.add(InboxRows.listRow(doc, isUnread(doc, owner),
                    messageCounts.get(doc.getId())));
        }

        MaximegalonService.BadgeCounts badge = threads.countBadge(tenantId, owner);
        MaximegalonService.PendingCounts pending =
                threads.countPending(tenantId, List.of(owner));

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("unread", badge.unread());
        counts.put("unreadRequiresAction", badge.unreadRequiresAction());
        counts.put("pending", pending.total());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        out.put("counts", counts);
        out.put("returned", rows.size());
        out.put("truncated", truncated);
        out.put("threads", rows);
        return out;
    }

    /**
     * Mirrors the server's rule: the thread's own body counts, and so does every
     * contribution. {@code unreadFor} is not mapped out to clients, so this
     * derives the same answer from {@code readBy} — and the list query projects
     * the messages away, which is why an unfetched thread can only be judged on
     * its own {@code readBy}.
     */
    private static boolean isUnread(MaximegalonDocument doc, String owner) {
        if (doc.getUnreadFor() != null) return doc.getUnreadFor().contains(owner);
        return doc.getReadBy() == null || !doc.getReadBy().contains(owner);
    }

    private static @org.jspecify.annotations.Nullable MaximegalonStatus parseStatus(
            @org.jspecify.annotations.Nullable Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) return null;
        try {
            return MaximegalonStatus.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolException("unknown status '" + s
                    + "' — one of PENDING, ANSWERED, DISMISSED, ARCHIVED.");
        }
    }

    private static boolean boolParam(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof Boolean b) return b;
        return raw instanceof String s && Boolean.parseBoolean(s);
    }

    private static @org.jspecify.annotations.Nullable String stringParam(
            Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
