package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads one matter: its question, its state, and the clarification posted on it.
 *
 * <p><b>Why this is {@code thread_*} and not {@code inbox_*}.</b> Maximegalon
 * carries two topics that share one document — the queue ("what is waiting on
 * me") and the clarification of one matter. The word "inbox" earns its keep on
 * the first, where a model's mail-shaped prior is mostly right; on the second
 * that prior is wrong and drags the model towards replying freely to a
 * correspondence. Splitting the families puts each word where it helps.
 *
 * <p>The discussion is paginated because a thread may hold 500 contributions:
 * shipping them all would blow the prompt, and shipping some silently would read
 * as "that is all there is". {@code omittedMessages} always says.
 */
@Component
@RequiredArgsConstructor
public class ThreadGetTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of(
                            "type", "string",
                            "description", "From inbox_list."),
                    "messageOffset", Map.of(
                            "type", "integer",
                            "description", "Where to start in the clarification, "
                                    + "oldest first. Default 0."),
                    "messageLimit", Map.of(
                            "type", "integer",
                            "description", "How many contributions to return. "
                                    + "Default 20, capped at 50.")),
            "required", List.of("threadId"));

    private final InboxToolSupport support;

    @Override public String name() { return "thread_get"; }

    @Override public String description() {
        return "Read one inbox thread: the matter itself, its state, and the contributions "
                + "made towards settling it. A thread is a single matter heading for at most "
                + "one decision, NOT an open-ended chat and NOT an email conversation — it "
                + "ends. Reading it changes nothing: it does not mark anything read and it "
                + "does not answer the question.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return true; }
    @Override public Set<String> labels() { return Set.of("read-only"); }

    @Override public String searchHint() {
        return "Read one inbox thread and the discussion on it";
    }

    @Override public String troubleshootingHint() {
        return "Thread ids come from inbox_list. Reading a thread never answers it — if a "
                + "person has to decide, say so instead of deciding for them.";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = support.tenantOrThrow(ctx);
        String threadId = requiredString(params, "threadId");
        MaximegalonDocument doc = support.loadVisible(tenantId, threadId, ctx);

        List<MaximegalonMessage> all = doc.getMessages() == null
                ? List.of() : doc.getMessages();
        int offset = Math.max(0, intParam(params, "messageOffset", 0));
        int limit = Math.min(Math.max(1, intParam(params, "messageLimit", DEFAULT_LIMIT)),
                MAX_LIMIT);
        // Past the end is not an error — an empty page with a correct
        // omittedMessages tells the caller exactly where it is.
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        List<MaximegalonMessage> page = all.subList(from, to);

        return InboxRows.thread(doc, page, from, all.size());
    }

    private static String requiredString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required — get one from inbox_list.");
        }
        return s.trim();
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
