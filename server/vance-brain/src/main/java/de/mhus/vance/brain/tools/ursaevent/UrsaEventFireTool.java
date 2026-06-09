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
                + "document_read on the resulting _vance/logs/events/... "
                + "document, plus targetName + spawnedId on success.";
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
        return Map.of(
                "correlationId", result.correlationId(),
                "targetName", result.workflowName(),
                "spawnedId", result.workflowRunId() == null ? "" : result.workflowRunId(),
                "logPath", logPath,
                "note", "Event fired. Read '" + logPath + "' via document_read for the per-trigger log.");
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
