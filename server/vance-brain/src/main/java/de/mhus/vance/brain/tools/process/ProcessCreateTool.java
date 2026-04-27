package de.mhus.vance.brain.tools.process;

import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
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
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spawns a new think-process inside the current session. Two paths:
 *
 * <ul>
 *   <li><b>Recipe path</b> — caller provides {@code recipe} (e.g.
 *       {@code "analyze"}). The {@link RecipeResolver} cascades
 *       project → tenant → bundled, returns engine name + default
 *       params + prompt-prefix + tool adjustments. Caller's
 *       {@code params} merge on top (per-key overrides) unless the
 *       recipe is locked.</li>
 *   <li><b>Direct-engine path</b> — caller provides {@code engine}
 *       (e.g. {@code "ford"}). No recipe lookup; just
 *       caller-supplied {@code params} as-is.</li>
 * </ul>
 *
 * <p>If both {@code recipe} and {@code engine} are set, {@code recipe}
 * wins and {@code engine} is logged-and-ignored — easier to reason
 * about than failing.
 *
 * <p>The new process is started immediately ({@code engine.start})
 * and arrives in
 * {@link de.mhus.vance.api.thinkprocess.ThinkProcessStatus#READY}.
 * Drive it via {@code process_steer}; stop it via {@code process_stop}.
 */
@Component
@Slf4j
public class ProcessCreateTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of(
                            "type", "string",
                            "description", "Stable process name, unique per session."),
                    "recipe", Map.of(
                            "type", "string",
                            "description", "Preferred — recipe name for cascade resolution. "
                                    + "Use `recipe_list` to see available recipes."),
                    "engine", Map.of(
                            "type", "string",
                            "description", "Direct-engine path: engine name "
                                    + "(e.g. 'ford'). Ignored if `recipe` is set."),
                    "title", Map.of(
                            "type", "string",
                            "description", "Optional human-readable title."),
                    "goal", Map.of(
                            "type", "string",
                            "description", "Optional one-line goal the engine should pursue."),
                    "params", Map.of(
                            "type", "object",
                            "description", "Engine-specific runtime parameters "
                                    + "(model, validation, maxIterations, …). With a recipe, "
                                    + "these override the recipe's defaults per-key.",
                            "additionalProperties", true)),
            "required", List.of("name"));

    private final ThinkProcessService thinkProcessService;
    /**
     * Lazy because the bean graph cycles otherwise:
     * {@code ThinkEngineService → ToolDispatcher → ServerToolSource → this}.
     * Resolving via {@link ObjectProvider} defers the lookup to
     * first use, by which time the singleton is built.
     */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final RecipeResolver recipeResolver;

    public ProcessCreateTool(
            ThinkProcessService thinkProcessService,
            ObjectProvider<ThinkEngineService> thinkEngineServiceProvider,
            RecipeResolver recipeResolver) {
        this.thinkProcessService = thinkProcessService;
        this.thinkEngineServiceProvider = thinkEngineServiceProvider;
        this.recipeResolver = recipeResolver;
    }

    @Override
    public String name() {
        return "process_create";
    }

    @Override
    public String description() {
        return "Create a new think-process in the current session and "
                + "start its engine. Pick a recipe name (preferred) or an "
                + "engine name. Returns the new process's name and status.";
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
        String recipeName = optString(params, "recipe");
        String engineName = optString(params, "engine");
        String title = optString(params, "title");
        String goal = optString(params, "goal");
        Map<String, Object> callerParams = optMap(params, "params");

        if (recipeName != null && engineName != null) {
            log.info("process_create called with both recipe='{}' and engine='{}' — recipe wins",
                    recipeName, engineName);
        }

        Optional<AppliedRecipe> applied;
        try {
            applied = recipeResolver.applyDefaulting(
                    ctx.tenantId(), ctx.projectId(),
                    recipeName, engineName, callerParams);
        } catch (RecipeResolver.UnknownRecipeException ure) {
            throw new ToolException(ure.getMessage());
        } catch (RecipeResolver.UnknownEngineException uee) {
            throw new ToolException(uee.getMessage());
        }

        ThinkProcessDocument fresh;
        try {
            if (applied.isPresent()) {
                fresh = createFromRecipeApplied(
                        ctx, sessionId, name, title, goal, applied.get());
            } else {
                fresh = createFromEngine(
                        ctx, sessionId, name, engineName, title, goal, callerParams);
            }
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
        if (refreshed.getRecipeName() != null) {
            out.put("recipe", refreshed.getRecipeName());
        }
        return out;
    }

    private ThinkProcessDocument createFromRecipeApplied(
            ToolInvocationContext ctx, String sessionId, String name,
            String title, String goal, AppliedRecipe applied) {
        ThinkEngine engine = thinkEngineServiceProvider.getObject().resolve(applied.engine())
                .orElseThrow(() -> new ToolException(
                        "Recipe '" + applied.name() + "' references unknown engine '"
                                + applied.engine() + "'"));
        return thinkProcessService.create(
                ctx.tenantId(), sessionId, name,
                engine.name(), engine.version(), title, goal,
                /*parentProcessId*/ ctx.processId(),
                applied.params(),
                applied.name(),
                applied.promptOverride(),
                applied.promptOverrideSmall(),
                applied.promptMode(),
                applied.intentCorrection(),
                applied.dataRelayCorrection(),
                applied.effectiveAllowedTools());
    }

    private ThinkProcessDocument createFromEngine(
            ToolInvocationContext ctx, String sessionId, String name,
            String engineName, String title, String goal,
            Map<String, Object> callerParams) {
        ThinkEngine engine = thinkEngineServiceProvider.getObject().resolve(engineName)
                .orElseThrow(() -> new ToolException(
                        "Unknown engine '" + engineName + "' — known: "
                                + thinkEngineServiceProvider.getObject().listEngines()));
        return thinkProcessService.create(
                ctx.tenantId(), sessionId, name,
                engine.name(), engine.version(), title, goal,
                /*parentProcessId*/ ctx.processId(),
                callerParams);
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

    private static Map<String, Object> optMap(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }
}
