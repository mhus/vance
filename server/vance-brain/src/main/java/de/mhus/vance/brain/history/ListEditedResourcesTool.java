package de.mhus.vance.brain.history;

import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The "what files have I touched" tool. Returns a deduplicated list of
 * typed resource keys ({@code CLIENT_FILE:/abs/path}, {@code WORKSPACE:&lt;process&gt;/rel},
 * {@code DOCUMENT:&lt;id&gt;}) drawn from the {@code RESOURCE:*} tags
 * across the calling process's chat history.
 *
 * <p>Designed as a <em>specialized</em> tool, not a granular building
 * block: one call gives the LLM a clean answer, no manual two-step over
 * {@link HistorySearchTool} required. {@code sinceTag} lets the LLM pin
 * the lookup to a marker in the history (e.g. {@code MODE:execute}
 * → "what did I edit since execution started") without translating
 * timestamps itself.
 *
 * <p>Resolution order when both {@code since} and {@code sinceTag} are
 * set: {@code sinceTag} wins if it resolves to a real marker; otherwise
 * {@code since} is honoured. When neither is set, the lookup spans the
 * full process lifetime.
 *
 * <p>Deferred — same discovery/activation pattern as
 * {@link HistorySearchTool} and {@link HistoryRecallTool}. See
 * {@code planning/process-history-search.md} v2 §13.
 */
@Component
@RequiredArgsConstructor
public class ListEditedResourcesTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "scope", Map.of(
                            "type", "string",
                            "description",
                                    "Process scope. 'process' (default) = only this process; "
                                            + "'children' = this process plus every descendant "
                                            + "via parentProcessId; 'session' = every process "
                                            + "in this session."),
                    "since", Map.of(
                            "type", "string",
                            "description",
                                    "Optional ISO-8601 timestamp — return only resources "
                                            + "touched at or after this moment. Overridden by "
                                            + "sinceTag when both resolve."),
                    "sinceTag", Map.of(
                            "type", "string",
                            "description",
                                    "Optional marker tag whose latest occurrence acts as the "
                                            + "time floor. Examples: PLAN_STEP_STARTED:cleanup, "
                                            + "MODE:execute, FILE_EDIT, ERROR. Resolved against "
                                            + "the same scope. When the tag is not found in "
                                            + "history, falls through to 'since' if set, else "
                                            + "to the start of the process.")),
            "required", List.of());

    private final ChatMessageService chatMessageService;
    private final ThinkProcessService thinkProcessService;

    @Override public String name() { return "list_edited_resources"; }

    @Override
    public String description() {
        return "List the deduplicated set of resources (client files, workspace "
                + "files, documents) that have been edited within this process. "
                + "Optional scope expands the lookup to children or the whole "
                + "session. Optional since / sinceTag bound the time window — "
                + "use sinceTag to anchor on a marker like a plan-step start.";
    }

    @Override public boolean primary() { return false; }
    @Override public boolean deferred() { return true; }

    @Override
    public String searchHint() {
        return "which files / documents have I touched, edited resources, "
                + "changed files this session, files changed since plan step";
    }

    @Override public Set<String> labels() { return Set.of("read-only"); }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null || ctx.processId().isBlank()) {
            throw new ToolException("list_edited_resources requires a process scope");
        }
        String scope = parseScope(params);
        Instant since = parseSince(params);
        String sinceTag = stringOrNull(params, "sinceTag");

        Set<String> allowedProcessIds = HistoryScopeResolver.resolve(
                scope, ctx, thinkProcessService);

        // sinceTag wins when it resolves to a real marker. Falling back
        // to the literal `since` (or to no floor at all) keeps the LLM's
        // intent intact when the marker hasn't been emitted yet.
        Instant effectiveSince = since;
        String resolvedFrom = since == null ? null : "since";
        if (sinceTag != null) {
            var markerTime = chatMessageService.findLatestCreatedAtForTag(
                    ctx.tenantId(), allowedProcessIds, sinceTag);
            if (markerTime.isPresent()) {
                effectiveSince = markerTime.get();
                resolvedFrom = "sinceTag:" + sinceTag;
            }
        }

        List<String> typedKeys = chatMessageService.distinctResourceKeys(
                ctx.tenantId(), allowedProcessIds, effectiveSince);

        List<Map<String, Object>> resources = new ArrayList<>(typedKeys.size());
        for (String typed : typedKeys) {
            int sep = typed.indexOf(':');
            if (sep < 0) continue; // defensive — shouldn't happen given the prefix filter
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", typed.substring(0, sep));
            entry.put("key", typed.substring(sep + 1));
            resources.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", resources);
        result.put("count", resources.size());
        result.put("scope", scope);
        if (effectiveSince != null) {
            result.put("since", effectiveSince.toString());
        }
        if (resolvedFrom != null) {
            result.put("resolvedFrom", resolvedFrom);
        }
        return result;
    }

    private static String parseScope(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("scope");
        if (raw == null) return HistorySearchTool.SCOPE_PROCESS;
        if (!(raw instanceof String s)) {
            throw new ToolException("'scope' must be a string");
        }
        String trimmed = s.trim().toLowerCase();
        if (trimmed.isEmpty()) return HistorySearchTool.SCOPE_PROCESS;
        if (!trimmed.equals(HistorySearchTool.SCOPE_PROCESS)
                && !trimmed.equals(HistorySearchTool.SCOPE_CHILDREN)
                && !trimmed.equals(HistorySearchTool.SCOPE_SESSION)) {
            throw new ToolException("'scope' must be one of: process, children, session "
                    + "(got '" + s + "')");
        }
        return trimmed;
    }

    private static @org.jspecify.annotations.Nullable Instant parseSince(
            Map<String, Object> params) {
        String raw = stringOrNull(params, "since");
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ToolException("'since' must be an ISO-8601 timestamp: " + raw);
        }
    }

    private static @org.jspecify.annotations.Nullable String stringOrNull(
            Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new ToolException("'" + key + "' must be a string");
        }
        return s.isBlank() ? null : s;
    }
}
