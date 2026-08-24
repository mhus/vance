package de.mhus.vance.brain.tools.inbox;

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
 * Marks named threads as read for their owner.
 *
 * <p>Exists because reading through a tool must <b>never</b> write read state.
 * {@code readBy} is a set of people; entering the owner because a machine looked
 * is a lie in the data — and the one lie that deletes an alarm. So marking is a
 * separate, named act, never a side effect, and its blast radius has to be
 * enumerable: the caller must name each id, which means it listed them first,
 * which means the transcript records which alarm was cleared.
 *
 * <p>Thread ids, not message ids. Partial marking exists for a human following a
 * deep link into the middle of a discussion; for an agent it would only be one
 * more way to get it wrong. One thread per id is the bound.
 */
@Component
@RequiredArgsConstructor
public class InboxMarkReadTool implements Tool {

    /** Enumerable means countable — a batch of 25 still fits in a transcript. */
    private static final int MAX_IDS = 25;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadIds", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "1-25 thread ids from inbox_list. "
                                    + "There is no filter and no 'all' — name them.")),
            "required", List.of("threadIds"));

    private final MaximegalonService threads;
    private final InboxToolSupport support;

    @Override public String name() { return "inbox_mark_read"; }

    @Override public String description() {
        return "Mark the named inbox threads as read for you, clearing their unread count. "
                + "Only call this when the user asked you to mark or clear something. "
                + "Never call it to tidy up after your own reading — the unread count is "
                + "their alarm, not your bookkeeping. Marking read never answers an ask.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override public String searchHint() {
        return "Mark inbox threads the user asked you to clear as read";
    }

    @Override public String troubleshootingHint() {
        return "Thread ids come from inbox_list. Marking read is not answering — an ask "
                + "stays open until a person decides it.";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = support.tenantOrThrow(ctx);
        String owner = support.ownerOrThrow(ctx);
        List<String> ids = InboxToolSupport.idList(params, "threadIds", MAX_IDS);

        List<String> marked = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (String threadId : ids) {
            // One unreachable id must not lose the work done on the others —
            // the caller would have to guess which of the batch landed.
            MaximegalonDocument doc;
            try {
                doc = support.loadVisible(tenantId, threadId, ctx);
            } catch (ToolException e) {
                skipped.add(reason(threadId, "not visible to you"));
                continue;
            }
            if (threads.markRead(tenantId, doc.getId(), owner).isPresent()) {
                marked.add(threadId);
            } else {
                skipped.add(reason(threadId, "vanished while marking"));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("marked", marked);
        out.put("skipped", skipped);
        return out;
    }

    private static Map<String, Object> reason(String threadId, String why) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("threadId", threadId);
        row.put("reason", why);
        return row;
    }
}
