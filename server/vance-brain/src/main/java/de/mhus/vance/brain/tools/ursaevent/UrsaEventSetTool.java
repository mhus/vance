package de.mhus.vance.brain.tools.ursaevent;

import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
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
 * Upsert an event — create if absent, replace YAML body if present.
 * Mirrors {@link de.mhus.vance.brain.tools.ursascheduler.UrsaSchedulerSetTool}
 * and {@link de.mhus.vance.brain.tools.hooks.HookSetTool}; the document
 * layer auto-archives the prior version on overwrite.
 *
 * <p>Unlike scheduler, events have no lockMode field — the bearer-token
 * + Setting-Cascade is the protection surface, and Settings are not
 * writable via this tool.
 *
 * <p>Response carries a {@code created} flag so the LLM can tell which
 * path ran.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@de.mhus.vance.toolpack.SpawnTool
public class UrsaEventSetTool implements Tool {

    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", Map.of(
                "type", "string",
                "description", "Event name — lowercase, alphanumeric + '_-', max 64 chars."));
        props.put("yaml", Map.of(
                "type", "string",
                "description", "Full YAML body. Must include exactly one of "
                        + "'recipe', 'workflow', 'script'. Optional fields: "
                        + "description, enabled, methods, auth, params, "
                        + "initialMessage, runAs, tags. See specification/events.md."));
        SCHEMA = Map.of(
                "type", "object",
                "properties", props,
                "required", List.of("name", "yaml"));
    }

    private final UrsaEventToolSupport support;

    @Override public String name() { return "event_set"; }

    @Override public String description() {
        return "Create or replace an event in the current project. Idempotent: "
                + "if an event with this name already exists locally its YAML "
                + "is overwritten (the previous version is auto-archived). "
                + "A cascade-resolved tenant entry is shadowed, not modified — "
                + "the write creates a project-local override. Response includes "
                + "'created: true|false'.";
    }

    @Override public boolean primary() { return false; }
    @Override public Map<String, Object> paramsSchema() { return SCHEMA; }
    @Override public Set<String> labels() { return Set.of("write", "events"); }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.projectId() == null) {
            throw new ToolException("event_set requires a project scope");
        }
        String name = UrsaEventToolSupport.normalizeName(stringOrThrow(params, "name"));
        String yaml = stringOrThrow(params, "yaml");

        ResolvedUrsaEvent validated = support.parseOrThrow(name, yaml);
        boolean replaced = support.upsert(ctx.tenantId(), ctx.projectId(), name, yaml, ctx.userId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("enabled", validated.enabled());
        out.put("created", !replaced);
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
