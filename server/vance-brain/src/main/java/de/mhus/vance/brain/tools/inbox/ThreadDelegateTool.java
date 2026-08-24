package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Hands a matter to the person it belongs to.
 *
 * <p>The one act in these families that genuinely takes work off someone.
 * Triage is read, understand, <b>route</b>, clear — and routing was the missing
 * step: "these three are Robin's, these two are mine". It <b>routes</b> a
 * decision, it does not make one, which is exactly why it is allowed where
 * answering is not.
 *
 * <p><b>Two gates, and the second one is the point.</b> Settling this thread is
 * the caller's right ({@code mayDecide}) — but delivering it needs
 * {@code WRITE} on the <em>recipient's</em> inbox, because handing someone a
 * matter spends their attention. That second check did not exist on either
 * delegate path when this tool was written: only a human clicking a colleague
 * out of a list could trigger it, which is a weak filter but a filter. Here the
 * target is a raw model parameter, so the gate had to come first.
 */
@Component
@RequiredArgsConstructor
public class ThreadDelegateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of(
                            "type", "string",
                            "description", "The thread to hand over, from inbox_list."),
                    "toUserId", Map.of(
                            "type", "string",
                            "description", "Login of the person who should decide it. "
                                    + "Name someone the user named — do not guess."),
                    "note", Map.of(
                            "type", "string",
                            "description", "Optional one line on why it is theirs. "
                                    + "They see it; write it for them, not for the log.")),
            "required", List.of("threadId", "toUserId"));

    private final MaximegalonService threads;
    private final InboxToolSupport support;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    @Override public String name() { return "thread_delegate"; }

    @Override public String description() {
        return "Hand an inbox thread to another person, so it waits on them instead of you. "
                + "Use it when the user says a matter belongs to somebody else. This ROUTES "
                + "the decision, it does not make it: the thread stays open and the new "
                + "assignee decides. Only name a person the user named — putting a matter on "
                + "someone's desk spends their attention, and it will be refused if you may "
                + "not write to their inbox.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("executive"); }

    @Override public String searchHint() {
        return "Hand an inbox thread over to the person who should decide it";
    }

    @Override public String troubleshootingHint() {
        return "Thread ids come from inbox_list. Delegating is not answering — the thread "
                + "stays open, it just waits on someone else.";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = support.tenantOrThrow(ctx);
        String owner = support.ownerOrThrow(ctx);
        String threadId = requiredString(params, "threadId");
        String toUserId = requiredString(params, "toUserId");
        String note = optString(params, "note");

        if (toUserId.equals(owner)) {
            throw new ToolException("'" + toUserId + "' is you — it is already on your desk. "
                    + "Nothing to hand over.");
        }

        MaximegalonDocument doc = support.loadVisible(tenantId, threadId, ctx);
        if (!support.mayDecide(tenantId, owner, doc)) {
            throw new ToolException("you may read '" + threadId + "' but not hand it on — "
                    + "that belongs to whoever it is assigned to.");
        }
        support.enforceWrite(tenantId, owner, doc);
        // The recipient's inbox, separately: the check above says this thread may
        // be settled, never whose desk it may land on.
        permissionService.enforce(
                contextFactory.forToolSubject(tenantId, owner),
                new Resource.InboxItem(tenantId, "", toUserId),
                Action.WRITE);

        MaximegalonDocument updated = threads
                .delegate(tenantId, threadId, toUserId, owner, note)
                .orElseThrow(() -> InboxToolSupport.notVisible(threadId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", threadId);
        out.put("assignedToUserId", updated.getAssignedToUserId());
        // Said explicitly, because "delegated" reads like "dealt with": it is
        // not. Somebody still has to decide it.
        out.put("stillOpen", updated.isRequiresAction());
        return out;
    }

    private static String requiredString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must not be empty.");
        }
        return s.trim();
    }

    private static @org.jspecify.annotations.Nullable String optString(
            Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
