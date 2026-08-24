package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.api.inbox.InboxMessagePostRequest;
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
 * Adds one contribution to a thread's clarification.
 *
 * <p>The only mutation an agent owns on a matter, and deliberately so: it is
 * what an agent has to offer — the finding, the check, the "this is moot now"
 * that lets a person decide faster. It settles nothing. Everything about the
 * naming works to keep that clear: it is a contribution to a clarification, not
 * a reply to a message, and the description says so in the negative because the
 * mail-shaped reading of "add message to thread" is exactly "answer it".
 *
 * <p>Author is the process owner, never a parameter — accepting one would let a
 * caller post under someone else's name.
 */
@Component
@RequiredArgsConstructor
public class ThreadMessageAddTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of(
                            "type", "string",
                            "description", "From inbox_list."),
                    "body", Map.of(
                            "type", "string",
                            "description", "Markdown, up to "
                                    + InboxMessagePostRequest.MAX_BODY_CHARS + " characters. "
                                    + "Say what you found or checked; do not state a "
                                    + "decision as if it were made."),
                    "parentId", Map.of(
                            "type", "string",
                            "description", "Optional: a root-level contribution you are "
                                    + "replying to. Depth is one level — omit it to post "
                                    + "at the root.")),
            "required", List.of("threadId", "body"));

    private final MaximegalonService threads;
    private final InboxToolSupport support;

    @Override public String name() { return "thread_message_add"; }

    @Override public String description() {
        return "Add one contribution to an inbox thread's clarification — a finding, a "
                + "check, a reason something is now moot. THIS DOES NOT ANSWER THE THREAD: "
                + "an ask stays open until a person decides it, and there is no tool that "
                + "decides for them. Use it to give the deciding person what they need, or "
                + "to bring a forgotten matter back into view.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return true; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override public String searchHint() {
        return "Contribute a finding or note to an inbox thread without answering it";
    }

    @Override public String troubleshootingHint() {
        return "Thread ids come from inbox_list. A contribution is not an answer — if the "
                + "thread is waiting on a decision, it still is afterwards.";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = support.tenantOrThrow(ctx);
        String owner = support.ownerOrThrow(ctx);
        String threadId = requiredString(params, "threadId");
        String body = requiredString(params, "body");
        if (body.length() > InboxMessagePostRequest.MAX_BODY_CHARS) {
            throw new ToolException("body is longer than "
                    + InboxMessagePostRequest.MAX_BODY_CHARS + " characters — shorten it, or "
                    + "write the long form into a document and reference it here.");
        }
        String parentId = optString(params, "parentId");

        // Contributing gates on maySee, not mayDecide: taking part in a
        // discussion is not settling it.
        MaximegalonDocument doc = support.loadVisible(tenantId, threadId, ctx);

        MaximegalonDocument updated;
        try {
            updated = threads.postMessage(tenantId, doc.getId(), owner, body, parentId)
                    .orElseThrow(() -> InboxToolSupport.notVisible(threadId));
        } catch (MaximegalonRuleException e) {
            throw new ToolException(explain(e, threadId, parentId));
        }

        List<?> messages = updated.getMessages() == null ? List.of() : updated.getMessages();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", threadId);
        out.put("messageCount", messages.size());
        // Returning the thread would put half the discussion back in the prompt
        // on every contribution; the new id is what the caller might need.
        if (!messages.isEmpty()) {
            Object last = messages.get(messages.size() - 1);
            if (last instanceof de.mhus.vance.shared.inbox.MaximegalonMessage m) {
                out.put("messageId", m.getId());
            }
        }
        return out;
    }

    /**
     * Turns a refused invariant into a sentence that says what to do instead.
     * The reason codes are stable; the wording is this layer's job, because
     * "message_limit_reached" tells a model nothing about its next move.
     */
    private static String explain(MaximegalonRuleException e, String threadId,
            @org.jspecify.annotations.Nullable String parentId) {
        return switch (e.getReason()) {
            case MaximegalonRuleException.MESSAGE_LIMIT_REACHED ->
                    "this thread holds " + MaximegalonService.MAX_MESSAGES
                            + " contributions, its limit. A matter that needs more is a new "
                            + "matter — open one with inbox_post.";
            case MaximegalonRuleException.INVALID_PARENT ->
                    "'" + parentId + "' cannot be replied to: it is either unknown in this "
                            + "thread or already a reply, and replies go one level deep. "
                            + "Post at the root level instead (omit parentId).";
            default -> e.getMessage() == null
                    ? "the thread refused this contribution: " + e.getReason()
                    : e.getMessage();
        };
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
