package de.mhus.vance.brain.tools.ursascheduler;

import de.mhus.vance.brain.ursascheduler.UrsaSchedulerService;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.ursascheduler.ResolvedUrsaScheduler;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Upsert a scheduler — create if absent, replace YAML body if present.
 * Replaces the earlier {@code scheduler_create}/{@code scheduler_update}
 * pair, which forced a two-call dance (try create → on "exists" use
 * update) that didn't buy anything safety-wise because the document
 * layer auto-archives every overwrite.
 *
 * <p>Response carries a {@code created} flag so the LLM can tell which
 * path ran.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@de.mhus.vance.toolpack.SpawnTool
public class UrsaSchedulerSetTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Scheduler name — lowercase, alphanumeric + '_-', max 64 chars."));
        props.put("yaml", Map.of(
                "type", "string",
                "description", "Full YAML body. Must include 'description', 'cron', 'recipe'. "
                        + "Optional fields: timezone, enabled, params, initialMessage, "
                        + "runAs, overlap, tags."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name", "yaml"));
    }

    private final UrsaSchedulerToolSupport support;
    private final DocumentService documentService;
    private final UrsaSchedulerService schedulerService;

    @Override public String name() { return "scheduler_set"; }

    @Override public String description() {
        return "Create or replace a scheduler in the current project. "
                + "Idempotent: if a scheduler with this name already exists "
                + "its YAML is overwritten (the previous version is auto-"
                + "archived). Response includes 'created: true|false' so the "
                + "caller can tell which path ran.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "scheduler"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("scheduler_set requires a project scope");
        }
        String name = UrsaSchedulerToolSupport.normalizeName(stringOrThrow(params, "name"));
        String yaml = stringOrThrow(params, "yaml");

        // Refuse if a cascade-resolved entry with this name is locked —
        // creating a project-local override would otherwise shadow a
        // protected tenant entry.
        support.guardMutation(ctx.tenantId(), ctx.projectId(), name);

        boolean existed = documentService.findByPath(ctx.tenantId(), ctx.projectId(),
                UrsaSchedulerToolSupport.pathFor(name)).isPresent();

        ResolvedUrsaScheduler validated = support.parseOrThrow(name, yaml);
        support.upsert(ctx.tenantId(), ctx.projectId(), name, yaml, ctx.userId());
        // The DocumentChangedEvent that support.upsert kicked off has
        // already routed through UrsaSchedulerDocumentListener → refreshOne;
        // a cheap registry probe gives us the boolean for the response
        // without a second refresh.
        boolean registered = schedulerService.isRegistered(
                ctx.tenantId(), ctx.projectId(), name);
        List<String> warnings = support.crossReferenceWarnings(
                ctx.tenantId(), ctx.projectId(), validated);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("enabled", validated.enabled());
        out.put("created", !existed);
        out.put("registered", registered);
        if (!warnings.isEmpty()) {
            out.put("warnings", warnings);
        }
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }
}
