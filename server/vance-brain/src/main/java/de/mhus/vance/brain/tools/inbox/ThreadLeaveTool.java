package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonRuleException;
import de.mhus.vance.shared.inbox.MaximegalonService;
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
 * Takes the caller out of a thread that is not theirs.
 *
 * <p><b>The counterpart to being invited.</b> Anybody with {@code WRITE} on an
 * account's inbox can put that account on a thread, and from then on every
 * contribution is unread for it. For a person that is a badge to ignore; for an
 * agent woken by unread threads it is a standing claim on its attention that
 * nobody can revoke. Without a way out, an invitation is a one-way door.
 *
 * <p><b>Self only, and that is the whole design.</b> Removing <em>somebody
 * else</em> is a different question — it decides who is in the room, needs
 * {@code mayDecide}, and belongs to whoever runs the matter. That path exists
 * over REST and is deliberately not a tool: an agent evicting a human from a
 * discussion is not a move it should be able to make in one call.
 *
 * <p><b>It cannot be used to duck a decision.</b> The assignee of an open ask is
 * refused ({@code ASSIGNEE_MUST_STAY}) — a process is waiting on that answer.
 * The way out of an ask is {@code thread_delegate}, which gives it to somebody
 * who can settle it, not silence.
 */
@Component
@RequiredArgsConstructor
public class ThreadLeaveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of(
                            "type", "string",
                            "description", "From inbox_list or a self-check finding."),
                    "note", Map.of(
                            "type", "string",
                            "description", "Optional: posted as a contribution before you "
                                    + "go. Leaving without a word looks like a failure to "
                                    + "whoever brought you in.")),
            "required", List.of("threadId"));

    private final MaximegalonService threads;
    private final InboxToolSupport support;

    @Override public String name() { return "thread_leave"; }

    @Override public String description() {
        return "Take yourself off an inbox thread: you stop being a participant and it stops "
                + "showing as unread for you. For threads that do not concern you — not for "
                + "ones you would rather not deal with. If the thread is waiting on YOUR "
                + "decision this is refused; hand it over with thread_delegate instead. "
                + "Leaving changes nothing about the matter itself.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override public String searchHint() {
        return "Stop following an inbox thread that does not concern you";
    }

    @Override public String troubleshootingHint() {
        return "Refused for the assignee of an open ask — delegate it instead. You can only "
                + "remove yourself; taking somebody else out is not a tool.";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = support.tenantOrThrow(ctx);
        String owner = support.ownerOrThrow(ctx);
        String threadId = requiredString(params, "threadId");
        String note = optString(params, "note");

        MaximegalonDocument doc = support.loadVisible(tenantId, threadId, ctx);
        // The refusal is asked first, because the note is not takeable back.
        // The note has to be posted *before* the leave — afterwards visibility
        // may be gone and the post would fail — which means a leave refused
        // after the fact leaves "I am off this, ask somebody else" standing on
        // a thread the author is still on. Same question the service asks
        // authoritatively a moment later; a race between the two only costs
        // the ordinary refusal below.
        if (MaximegalonService.mustStay(doc, owner)) {
            throw assigneeMustStay();
        }
        if (note != null) {
            threads.postMessage(tenantId, doc.getId(), owner, note, null);
        }

        MaximegalonDocument updated;
        try {
            updated = threads.setFollowing(tenantId, doc.getId(), owner, /*following*/ false)
                    .orElseThrow(() -> InboxToolSupport.notVisible(threadId));
        } catch (MaximegalonRuleException e) {
            if (MaximegalonRuleException.ASSIGNEE_MUST_STAY.equals(e.getReason())) {
                throw assigneeMustStay();
            }
            throw new ToolException(e.getMessage() == null ? e.getReason() : e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", threadId);
        out.put("left", true);
        // Said out loud because "I am off it" and "it is handled" are the two
        // things easiest to confuse here, and only one of them is true.
        out.put("note", "You will not hear about this thread again. Nothing about the "
                + "matter changed — its status is still " + updated.getStatus() + ".");
        return out;
    }

    /** The one refusal this tool has, worded once for both places that raise it. */
    private static ToolException assigneeMustStay() {
        return new ToolException("this thread is waiting on your decision, so you "
                + "cannot leave it. Answer it, or give it to somebody who can decide "
                + "with thread_delegate.");
    }

    private static String requiredString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof String s && !s.isBlank()) return s.trim();
        throw new ToolException("'" + key + "' is required");
    }

    private static @org.jspecify.annotations.Nullable String optString(
            Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
