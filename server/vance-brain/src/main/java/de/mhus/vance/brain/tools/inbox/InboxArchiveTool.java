package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
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
 * Takes settled threads off the desk — off the list, still in the record.
 *
 * <p>The only settling act in either family, allowed for two reasons: it is
 * reversible, and the dangerous case is sharply nameable. That case is an open
 * ask, and it is guarded: archiving one would hide a decision something may be
 * blocked on.
 *
 * <p><b>A batch, with the guard applied per item.</b> The first version took one
 * thread per call, on the reasoning that a batch would blur the check. It does
 * not — the check is per item either way, and every outcome is reported per item.
 * What one-per-call did blur is the actual job: "clear the thirty outputs I have
 * read" was thirty round-trips, which is exactly where taking work off someone
 * stops being worth it. One refusal never costs the others: it lands in
 * {@code skipped} and the rest proceed.
 */
@Component
@RequiredArgsConstructor
public class InboxArchiveTool implements Tool {

    /** Same bound as {@code inbox_mark_read}: named ids stay countable. */
    private static final int MAX_IDS = 25;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadIds", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "1-25 thread ids from inbox_list.")),
            "required", List.of("threadIds"));

    private final MaximegalonService threads;
    private final InboxToolSupport support;

    @Override public String name() { return "inbox_archive"; }

    @Override public String description() {
        return "Archive settled inbox threads — they leave the list but stay in the record "
                + "and can be brought back by a person. Refused per thread for anything "
                + "still waiting on an answer: archiving that would hide a decision. Check "
                + "`skipped` in the result for what was refused and why. Archiving is not "
                + "answering, and it does not touch what you have read.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("executive"); }

    @Override public String searchHint() {
        return "Clear finished inbox threads off the user's list";
    }

    @Override public String troubleshootingHint() {
        return "Thread ids come from inbox_list. An open ask cannot be archived — add a "
                + "contribution with thread_message_add explaining why it is moot instead.";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = support.tenantOrThrow(ctx);
        String owner = support.ownerOrThrow(ctx);
        List<String> ids = InboxToolSupport.idList(params, "threadIds", MAX_IDS);

        List<String> archived = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (String threadId : ids) {
            try {
                archiveOne(tenantId, owner, threadId, ctx, archived);
            } catch (ToolException e) {
                // Per item, so one refusal does not cost the rest — and so the
                // reason stays attached to the id it belongs to.
                skipped.add(reason(threadId, e.getMessage()));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("archived", archived);
        out.put("skipped", skipped);
        return out;
    }

    private void archiveOne(String tenantId, String owner, String threadId,
            ToolInvocationContext ctx, List<String> archived) {
        MaximegalonDocument doc = support.loadVisible(tenantId, threadId, ctx);

        // Already there: the service is idempotent, so this is a statement of
        // fact rather than a failure. Counting it as archived keeps a re-run
        // from reading as a partial failure.
        if (doc.getStatus() == MaximegalonStatus.ARCHIVED) {
            archived.add(threadId);
            return;
        }

        // Here the distinction between "may see" and "may settle" is allowed to
        // show: existence is already known via thread_get, so a precise message
        // leaks nothing and saves the model from guessing.
        if (!support.mayDecide(tenantId, owner, doc)) {
            throw new ToolException("you may read this thread but not settle it — "
                    + "archiving belongs to whoever it is assigned to.");
        }
        support.enforceWrite(tenantId, owner, doc);

        if (doc.getStatus() == MaximegalonStatus.PENDING && doc.isRequiresAction()) {
            throw new ToolException("this is an open request waiting on a person, and "
                    + "archiving it would hide a decision something may be blocked on. "
                    + "Leave it, or add a contribution explaining why it is moot.");
        }

        threads.archive(tenantId, threadId, owner)
                .orElseThrow(() -> InboxToolSupport.notVisible(threadId));
        archived.add(threadId);
    }

    private static Map<String, Object> reason(String threadId, String why) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("threadId", threadId);
        row.put("reason", why);
        return row;
    }

}
