package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.shared.inbox.MaximegalonDocument;
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
 * Pulls somebody into a thread they should be seeing.
 *
 * <p><b>Inviting is delivering.</b> It creates unread for the invitee — a badge
 * on somebody else's screen — so it is gated like a delivery: {@code WRITE} on
 * the <em>invitee's</em> inbox, the same check the REST endpoint and Milliways'
 * inbox handler make. Being able to see a thread says nothing about whether you
 * may push it at a stranger.
 *
 * <p><b>One per call, on purpose.</b> The batch shape of {@code inbox_archive}
 * is right for acting on one's own queue and wrong here: the blast radius of a
 * mistake is other people's attention, and a list parameter is an invitation to
 * name six of them. One invitee means one decision, recorded once in the
 * thread's history.
 *
 * <p>Inviting is not delegating. The invitee gains sight of the matter and the
 * ability to contribute, not the right to settle it — {@code thread_delegate}
 * is what hands an ask over.
 */
@Component
@RequiredArgsConstructor
public class ThreadInviteTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of(
                            "type", "string",
                            "description", "From inbox_list or a self-check finding."),
                    "userId", Map.of(
                            "type", "string",
                            "description", "The login name of one Vancetope account. One "
                                    + "person per call — invite again for a second."),
                    "reason", Map.of(
                            "type", "string",
                            "description", "Optional: posted as a contribution before the "
                                    + "invitation, so the person arrives knowing why. Without "
                                    + "it they get a thread and no explanation.")),
            "required", List.of("threadId", "userId"));

    private final MaximegalonService threads;
    private final InboxToolSupport support;

    @Override public String name() { return "thread_invite"; }

    @Override public String description() {
        return "Add one person to an inbox thread so they see it and can contribute. THIS "
                + "PUTS A BADGE ON THEIR SCREEN — invite someone because the matter needs "
                + "them, not to keep them informed. It does not hand the decision over: "
                + "use thread_delegate for that.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return true; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override public String searchHint() {
        return "Bring another person into an inbox thread";
    }

    @Override public String troubleshootingHint() {
        return "userId is a login name, not a display name. If it is refused, you do not "
                + "have permission to deliver into that person's inbox.";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = support.tenantOrThrow(ctx);
        String owner = support.ownerOrThrow(ctx);
        String threadId = requiredString(params, "threadId");
        String userId = requiredString(params, "userId");
        String reason = optString(params, "reason");

        MaximegalonDocument doc = support.loadVisible(tenantId, threadId, ctx);
        if (userId.equals(owner)) {
            throw new ToolException("you are already on this thread — thread_invite is for "
                    + "bringing somebody else in.");
        }
        // Before the invitation, not after: an invitation makes the thread
        // unread for them, and a person who opens it should already find the
        // reason there rather than a thread that appeared for no stated cause.
        support.enforceWriteOnInboxOf(tenantId, owner, userId);
        if (reason != null) {
            threads.postMessage(tenantId, doc.getId(), owner, reason, null);
        }

        MaximegalonDocument updated = threads.invite(tenantId, doc.getId(), userId, owner)
                .orElseThrow(() -> InboxToolSupport.notVisible(threadId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", threadId);
        out.put("invited", userId);
        out.put("participants", updated.getParticipants() == null
                ? List.of() : List.copyOf(updated.getParticipants()));
        out.put("note", "It is unread for them now. They can see and contribute; the "
                + "decision, if there is one, is still where it was.");
        return out;
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
