package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.api.inbox.MaximegalonStatus;
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
 * Takes a settled thread off the desk — off the list, still in the record.
 *
 * <p>The only settling act in either family, allowed for two reasons: it is
 * reversible, and the dangerous case is sharply nameable. That case is an open
 * ask, and it is guarded: archiving one would hide a decision something may be
 * blocked on, so the tool refuses and says what to do instead.
 *
 * <p>One thread per call, deliberately unlike {@code inbox_mark_read}: this
 * changes {@code status} on a shared object, and a batch would blur the check
 * per item.
 */
@Component
@RequiredArgsConstructor
public class InboxArchiveTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of(
                            "type", "string",
                            "description", "The thread to archive, from inbox_list.")),
            "required", List.of("threadId"));

    private final MaximegalonService threads;
    private final InboxToolSupport support;

    @Override public String name() { return "inbox_archive"; }

    @Override public String description() {
        return "Archive one settled inbox thread — it leaves your list but stays in the "
                + "record and can be brought back by a person. Refused for a thread still "
                + "waiting on an answer: archiving that would hide a decision. Archiving is "
                + "not answering, and it does not touch what you have read.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("executive"); }

    @Override public String searchHint() {
        return "Clear a finished inbox thread off the user's list";
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
        String threadId = requiredString(params, "threadId");

        MaximegalonDocument doc = support.loadVisible(tenantId, threadId, ctx);

        // Already there: the service is idempotent, so this is a statement of
        // fact rather than a failure. Telling the model it "failed" would invite
        // a retry loop over something already true.
        if (doc.getStatus() == MaximegalonStatus.ARCHIVED) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("threadId", threadId);
            out.put("status", MaximegalonStatus.ARCHIVED.name());
            out.put("alreadyArchived", true);
            return out;
        }

        // Here the distinction between "may see" and "may settle" is allowed to
        // show: existence is already known via thread_get, so a precise message
        // leaks nothing and saves the model from guessing.
        if (!support.mayDecide(tenantId, owner, doc)) {
            throw new ToolException("you may read '" + threadId + "' but not settle it — "
                    + "archiving belongs to whoever it is assigned to.");
        }
        support.enforceWrite(tenantId, owner, doc);

        if (doc.getStatus() == MaximegalonStatus.PENDING && doc.isRequiresAction()) {
            throw new ToolException("'" + threadId + "' is an open request waiting on a "
                    + "person, and archiving it would hide a decision something may be "
                    + "blocked on. Leave it, or add a contribution explaining why it is moot.");
        }

        MaximegalonDocument archived = threads.archive(tenantId, threadId, owner)
                .orElseThrow(() -> InboxToolSupport.notVisible(threadId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", threadId);
        out.put("status", archived.getStatus() == null
                ? MaximegalonStatus.ARCHIVED.name() : archived.getStatus().name());
        return out;
    }

    private static String requiredString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required — get one from inbox_list.");
        }
        return s.trim();
    }
}
