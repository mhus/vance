package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.brain.inbox.InboxAuthz;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What every {@code inbox_*} and {@code thread_*} tool needs before it may look
 * at anything: who is asking, and may they see this thread.
 *
 * <p><b>No SYSTEM fallback.</b> {@link SecurityContextFactory#forToolSubject}
 * answers a blank {@code userId} with {@link
 * de.mhus.vance.shared.permission.SecurityContext#SYSTEM}, which passes R1 and
 * would therefore return every thread in the tenant. For {@code inbox_post}
 * that default is right — a headless scheduler may deliver. For reading a
 * personal inbox there is, in that case, no person: the answer is "nothing to
 * read", never "everything". Same call the house already made in
 * {@code MagratheaGateChatAnswerService.mayAnswer} — a gate a scheduler-authored
 * line can close is not a gate.
 *
 * <p>A service account is unaffected: {@code _daemon-prod-01} has a
 * {@code userId} and therefore an inbox of its own.
 */
@Component
@RequiredArgsConstructor
public class InboxToolSupport {

    /**
     * Deliberately the same sentence for every tool in both families. It says
     * what is true (there is no inbox here) rather than what failed, because a
     * model that reads "denied" looks for another way in.
     */
    static final String NO_OWNER =
            "inbox tools act as the process owner, and this process has no user "
                    + "bound — there is no inbox to read.";

    private final MaximegalonService threads;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;
    private final InboxAuthz authz;

    /** The tenant of the call, or a refusal. */
    String tenantOrThrow(ToolInvocationContext ctx) {
        String tenantId = ctx.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new ToolException("inbox tools require a tenant scope");
        }
        return tenantId;
    }

    /** The person the call acts as, or a refusal. Never SYSTEM. */
    String ownerOrThrow(ToolInvocationContext ctx) {
        String userId = ctx.userId();
        if (userId == null || userId.isBlank()) {
            throw new ToolException(NO_OWNER);
        }
        return userId;
    }

    /**
     * Loads a thread the caller may see, in the order the REST surface uses:
     * <b>provider first, document second</b>. Only the provider keeps a
     * stranger out; only the document lets a participant in who is neither
     * assignee nor team-mate — so both are needed, and swapping them would let
     * an invitation bypass the provider.
     *
     * <p>Invisible and absent give the <b>same</b> message, as REST answers both
     * with 404: a distinguishing message would confirm the thread exists.
     */
    MaximegalonDocument loadVisible(String tenantId, String threadId, ToolInvocationContext ctx) {
        String owner = ownerOrThrow(ctx);
        MaximegalonDocument doc = threads.findById(tenantId, threadId)
                .orElseThrow(() -> notVisible(threadId));
        boolean allowed = permissionService.check(
                contextFactory.forToolSubject(tenantId, owner),
                new Resource.InboxItem(tenantId, threadId,
                        doc.getAssignedToUserId() == null ? "" : doc.getAssignedToUserId()),
                Action.READ);
        if (!allowed && !authz.maySee(tenantId, owner, doc)) {
            throw notVisible(threadId);
        }
        return doc;
    }

    /** Whether the caller may settle this thread — archiving counts as settling. */
    boolean mayDecide(String tenantId, String owner, MaximegalonDocument doc) {
        return authz.mayDecide(tenantId, owner, doc.getAssignedToUserId());
    }

    void enforceWrite(String tenantId, String owner, MaximegalonDocument doc) {
        permissionService.enforce(
                contextFactory.forToolSubject(tenantId, owner),
                new Resource.InboxItem(tenantId,
                        doc.getId() == null ? "" : doc.getId(),
                        doc.getAssignedToUserId() == null ? "" : doc.getAssignedToUserId()),
                Action.WRITE);
    }

    /**
     * {@code WRITE} on <em>someone else's</em> inbox — the delivery gate.
     *
     * <p>Separate from {@link #enforceWrite}, which asks about the thread the
     * caller already holds. Inviting is not an operation on that thread, it is
     * a delivery into the invitee's inbox, and the two questions have different
     * answers: seeing a thread says nothing about whether you may push it at a
     * stranger. Same resource shape the REST endpoint and Milliways' inbox
     * handler use — no thread id, because the thread is not what is being
     * authorized.
     */
    void enforceWriteOnInboxOf(String tenantId, String owner, String targetUserId) {
        permissionService.enforce(
                contextFactory.forToolSubject(tenantId, owner),
                new Resource.InboxItem(tenantId, "", targetUserId),
                Action.WRITE);
    }

    /**
     * Reads a bounded, explicitly named id list. Shared so the two batch tools
     * cannot drift apart on the bound — and because "name them, do not filter"
     * is the same rule in both: an enumerable blast radius is one the transcript
     * records.
     *
     * <p>A bare string is accepted as the obvious slip; refusing it would cost a
     * turn for something unambiguous.
     */
    static java.util.List<String> idList(
            @org.jspecify.annotations.Nullable Map<String, Object> params,
            String key, int max) {
        Object raw = params == null ? null : params.get(key);
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (raw instanceof java.util.List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) ids.add(s.trim());
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            ids.add(s.trim());
        }
        if (ids.isEmpty()) {
            throw new ToolException("'" + key + "' is required — name at least one thread "
                    + "id from inbox_list.");
        }
        if (ids.size() > max) {
            throw new ToolException("at most " + max
                    + " threads per call — name them in batches.");
        }
        return ids;
    }

    static ToolException notVisible(String threadId) {
        return new ToolException("no inbox thread '" + threadId + "' is visible to you.");
    }
}
