package de.mhus.vance.brain.tools.ursaevent;

import de.mhus.vance.brain.ursaeventtrigger.UrsaEventLogService;
import de.mhus.vance.brain.ursaeventtrigger.UrsaEventService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Trigger a configured UrsaEvent from the current project scope,
 * bypassing the webhook bearer-token check — the engine is already
 * trust-checked by the tenant/project gate, so demanding the same
 * secret the external Webhook caller uses would just force operators
 * to expose tokens inside the prompt.
 *
 * <p>This is the events counterpart of {@code scheduler_fire}: the
 * model can verify an event end-to-end ("does my workflow actually
 * start when the webhook fires?") without needing the external
 * caller's secret. Routes through
 * {@link UrsaEventService#triggerAdmin} — same metric/log surface as
 * the UI-based test-fire button, so the run is later distinguishable
 * from public-trigger ones via {@code source: admin} on the
 * scheduler-log document.
 *
 * <p>Returns the {@code correlationId}, the project-relative
 * {@code logPath} of the per-trigger log document, the
 * resolved {@code targetName} (recipe/workflow/script) and the
 * {@code spawnedId} (process or workflow run id) when the event
 * fired successfully. On failure the tool surfaces the underlying
 * {@link ResponseStatusException} reason as a {@link ToolException};
 * the matching log document is still written so the engine can
 * inspect the trace afterwards.
 */
@Component
@RequiredArgsConstructor
public class UrsaEventFireTool implements Tool {

    /**
     * Cap on the output handed back to the model. See {@link #capOutput}.
     */
    private static final int OUTPUT_MAX_CHARS = 4000;

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Event name (without .yaml suffix), as it appears under _vance/events/."));
        props.put("payload", Map.of(
                "type", "object",
                "description", "Optional JSON payload — exposed to the spawned "
                        + "workflow/recipe under the 'payload' params key. "
                        + "Mirrors what an external webhook caller would POST."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name"));
    }

    private final UrsaEventService eventService;

    @Override public String name() { return "event_fire"; }

    @Override public String description() {
        return "Trigger a configured UrsaEvent from the current project, "
                + "bypassing the webhook bearer-token check. Returns "
                + "correlationId + logPath so the run can be inspected via "
                + "doc_read on the resulting _vance/logs/events/... "
                + "document, plus targetName + spawnedId on success. "
                + "A 'script:' event that is not configured async returns its "
                + "result under 'output' — those events are usable as ordinary "
                + "function calls. Spawns (recipe/workflow), async events, and "
                + "events that set outputToAgents:false return no 'output'; "
                + "do not retry waiting for one, read the log instead.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("admin", "events"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("event_fire requires a project scope");
        }
        String name = stringOrThrow(params, "name").trim();
        Object payload = params == null ? null : params.get("payload");
        String triggeredBy = ctx.userId() == null ? "agent" : "agent:" + ctx.userId();

        UrsaEventService.UrsaEventTriggerResult result;
        try {
            result = eventService.triggerAdmin(ctx.tenantId(), ctx.projectId(), name, payload, triggeredBy);
        } catch (ResponseStatusException ex) {
            // Surface the server-side reason so the engine sees why
            // the trigger was refused (not_found, disabled,
            // magrathea_unavailable, spawn_failed, …). The matching
            // log document was already written by UrsaEventService's
            // finally block — except for not_found which is
            // intentionally skip-logged.
            String reason = ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason();
            throw new ToolException(reason);
        }

        // The Result echoes the exact firedAt and correlationId the
        // log writer used, so pathFor() lands on the same document
        // the service just wrote — no folder-listing round-trip needed.
        String logPath = UrsaEventLogService.pathFor(name, result.firedAt(), result.correlationId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("correlationId", result.correlationId());
        out.put("targetName", result.workflowName());
        out.put("spawnedId", result.workflowRunId() == null ? "" : result.workflowRunId());
        out.put("logPath", logPath);
        if (result.output() != null && !result.output().isEmpty()) {
            out.put("output", capOutput(result.output()));
            out.put("note", "Event ran to completion; 'output' is its result.");
        } else {
            out.put("note", "Event fired without a result — it is a spawn, is configured "
                    + "async: true, or withholds its output from agents. Read '" + logPath
                    + "' via doc_read for the per-trigger log.");
        }
        return out;
    }

    /**
     * Bounds what a script's return value may add to the model's context.
     *
     * <p>A script may return arbitrarily much and the result lands
     * verbatim in the conversation. Truncation is marked so the model can
     * tell a complete answer from a clipped one instead of reasoning over
     * a silently halved string.
     */
    private static Map<String, Object> capOutput(Map<String, Object> output) {
        String rendered = String.valueOf(output);
        if (rendered.length() <= OUTPUT_MAX_CHARS) {
            return output;
        }
        return Map.of(
                "truncated", true,
                "totalChars", rendered.length(),
                "value", rendered.substring(0, OUTPUT_MAX_CHARS));
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
