package de.mhus.vance.brain.tools.inbox;

import de.mhus.vance.api.inbox.InboxReactRequest;
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
 * Puts one emoji reaction on a thread or on a single contribution — the quiet
 * acknowledgement.
 *
 * <p><b>Why an agent gets this at all.</b> Reactions are the channel that says
 * something without ringing a bell: they never mark anything unread, never
 * decide anything, never sort anything. That is exactly the shape of the one
 * thing an agent otherwise cannot communicate — "I saw this, and it needs
 * nothing". Read state is per-person and invisible to everyone else, so without
 * a reaction the human who posted cannot tell "looked at, fine" from "never
 * arrived".
 *
 * <p><b>And why it is dangerous.</b> It is the cheapest action in the whole
 * inventory, which makes it the obvious way to end a turn without doing the
 * work. The rule belongs in the prompt of whoever gets this tool and it is one
 * line: <em>a reaction is a receipt, not a result.</em> It replaces neither a
 * contribution nor a report — it is the whole answer only when there was
 * nothing to do.
 *
 * <p>{@code key} is a <b>shortcode</b>, not the character: skin-tone variants
 * are separate codepoints and would file the same reaction twice.
 */
@Component
@RequiredArgsConstructor
public class ThreadReactTool implements Tool {

    /**
     * What a machine may put on a thread. Not a style guide — a bound on
     * meaning: every one of these reads the same way to everybody, which is the
     * only property that makes a wordless signal worth anything. An open field
     * would let a model invent a private vocabulary nobody can read back, on
     * the one channel that carries no words to explain itself.
     */
    private static final Map<String, String> ALLOWED = Map.of(
            "eyes", "seen — looked at it, nothing needed from me",
            "thumbsup", "agreed / will do",
            "white_check_mark", "done — the thing this asked for has happened",
            "question", "unclear to me, I need more before I can act",
            "warning", "there is a problem with this",
            "hourglass", "picked up, still running");

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "threadId", Map.of(
                            "type", "string",
                            "description", "From inbox_list or a self-check finding."),
                    "key", Map.of(
                            "type", "string",
                            "enum", List.copyOf(ALLOWED.keySet()),
                            "description", "eyes = seen, nothing needed. thumbsup = agreed. "
                                    + "white_check_mark = done. question = unclear to me. "
                                    + "warning = there is a problem. hourglass = picked up, "
                                    + "still running."),
                    "messageId", Map.of(
                            "type", "string",
                            "description", "Optional: react to one contribution instead of "
                                    + "the thread itself. Ids come from thread_get."),
                    "on", Map.of(
                            "type", "boolean",
                            "description", "Omit or true to add it, false to take yours back.")),
            "required", List.of("threadId", "key"));

    private final MaximegalonService threads;
    private final InboxToolSupport support;

    @Override public String name() { return "thread_react"; }

    @Override public String description() {
        return "Put one emoji reaction on an inbox thread or one of its contributions — the "
                + "quiet way to tell the people on it what you did with it. A REACTION IS A "
                + "RECEIPT, NOT A RESULT: it decides nothing, answers nothing, and notifies "
                + "nobody. Use 'eyes' when you looked and nothing was needed. If something "
                + "IS needed, do that — a reaction is not a substitute for a contribution or "
                + "a report.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }
    @Override public boolean contributesPrak() { return false; }
    @Override public Set<String> labels() { return Set.of("write"); }

    @Override public String searchHint() {
        return "Acknowledge an inbox thread with an emoji without writing a message";
    }

    @Override public String troubleshootingHint() {
        return "Only six keys are accepted, and they are shortcodes ('thumbsup'), not "
                + "characters. Reacting never answers an ask and never notifies anyone.";
    }

    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String tenantId = support.tenantOrThrow(ctx);
        String owner = support.ownerOrThrow(ctx);
        String threadId = requiredString(params, "threadId");
        String key = requiredString(params, "key").toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED.containsKey(key)) {
            throw new ToolException("'" + key + "' is not one of the reactions a process may "
                    + "use. Pick from: " + String.join(", ", ALLOWED.keySet())
                    + ". They are shortcodes, not emoji characters.");
        }
        String messageId = optString(params, "messageId");
        boolean on = !(params.get("on") instanceof Boolean b) || b;

        // maySee, not mayDecide: reacting is taking part, not settling.
        MaximegalonDocument doc = support.loadVisible(tenantId, threadId, ctx);

        MaximegalonDocument updated;
        try {
            updated = threads.react(tenantId, doc.getId(), messageId, key, owner, on)
                    .orElseThrow(() -> InboxToolSupport.notVisible(threadId));
        } catch (MaximegalonRuleException e) {
            if (MaximegalonRuleException.REACTION_LIMIT_REACHED.equals(e.getReason())) {
                throw new ToolException("this node already carries "
                        + MaximegalonService.MAX_REACTION_KEYS + " distinct reactions, its "
                        + "limit — join one that is already there instead of adding another.");
            }
            throw new ToolException(e.getMessage() == null ? e.getReason() : e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threadId", threadId);
        out.put("key", key);
        out.put("on", on);
        out.put("means", ALLOWED.get(key));
        // Said back deliberately: the tool is silent by design, and a caller
        // that gets no confirmation of *what it just signalled* will reach for
        // a message to be sure it was heard.
        if (messageId != null) {
            out.put("messageId", messageId);
        }
        out.put("note", "Nobody was notified — a reaction is quiet by design.");
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
        if (raw instanceof String s && !s.isBlank()) {
            String trimmed = s.trim();
            return trimmed.length() > InboxReactRequest.MAX_KEY_CHARS
                    ? trimmed.substring(0, InboxReactRequest.MAX_KEY_CHARS) : trimmed;
        }
        return null;
    }
}
