package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.tools.Tool;
import de.mhus.vance.brain.tools.ToolException;
import de.mhus.vance.brain.tools.ToolInvocationContext;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spawns a new think-process inside the current session. Useful when
 * the engine wants a sub-agent: e.g. start a parallel "researcher"
 * process while the orchestrator keeps talking with the user.
 *
 * <p>The new process is started immediately ({@code engine.start})
 * and arrives in {@link de.mhus.vance.api.thinkprocess.ThinkProcessStatus#READY}.
 * Drive it via {@code process_steer}.
 */
@Component
public class ProcessCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Stable process name, unique per session."),
                    "engine", Map.of(
                            "type", "string",
                            "description", "Engine name, e.g. 'zaphod'."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional human-readable title."),
                    "goal", Map.of(
                            "type", "string",
                            "description", "Optional one-line goal the engine should pursue.")),
            "required", List.of("name", "engine"));

    private final ThinkProcessService thinkProcessService;
    /**
     * Lazy because the bean graph cycles otherwise:
     * {@code ThinkEngineService → ToolDispatcher → ServerToolSource → this}.
     * Resolving via {@link ObjectProvider} defers the lookup to
     * first use, by which time the singleton is built.
     */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;

    public ProcessCreateTool(
            ThinkProcessService thinkProcessService,
            ObjectProvider<ThinkEngineService> thinkEngineServiceProvider) {
        this.thinkProcessService = thinkProcessService;
        this.thinkEngineServiceProvider = thinkEngineServiceProvider;
    }

    @Override
    public String name() {
        return "process_create";
    }

    @Override
    public String description() {
        return "Create a new think-process in the current session and "
                + "start its engine. Returns the new process's name and "
                + "status.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        String sessionId = ctx.sessionId();
        if (sessionId == null) {
            throw new ToolException("process_create requires a session scope");
        }
        String name = stringOrThrow(params, "name");
        String engineName = stringOrThrow(params, "engine");
        String title = optString(params, "title");
        String goal = optString(params, "goal");

        ThinkEngine engine = thinkEngineServiceProvider.getObject().resolve(engineName)
                .orElseThrow(() -> new ToolException(
                        "Unknown engine '" + engineName + "' — known: "
                                + thinkEngineServiceProvider.getObject().listEngines()));

        ThinkProcessDocument fresh;
        try {
            fresh = thinkProcessService.create(
                    ctx.tenantId(), sessionId, name,
                    engine.name(), engine.version(), title, goal,
                    /*parentProcessId*/ ctx.processId());
        } catch (ThinkProcessService.ThinkProcessAlreadyExistsException e) {
            throw new ToolException(e.getMessage());
        }
        try {
            thinkEngineServiceProvider.getObject().start(fresh);
        } catch (RuntimeException e) {
            throw new ToolException(
                    "Engine start failed for '" + name + "': " + e.getMessage(), e);
        }

        ThinkProcessDocument refreshed = thinkProcessService.findById(fresh.getId())
                .orElse(fresh);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", refreshed.getName());
        out.put("status", refreshed.getStatus() == null ? null : refreshed.getStatus().name());
        out.put("engine", refreshed.getThinkEngine());
        out.put("engineVersion", refreshed.getThinkEngineVersion());
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("'" + key + "' is required and must be a non-empty string");
        }
        return s;
    }

    private static String optString(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
