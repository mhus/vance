package de.mhus.vance.brain.benjy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Recipe-configurable pipeline: mechanics are fixed, the semantic stages
 * are features the recipe switches on and parameterises
 * (planning/benjy-engine.md §4d, decision #19).
 *
 * <p>Parsed fail-fast from the process's effective {@code engineParams}:
 *
 * <pre>
 * params:
 *   doRecipe: benjy-do-coding            # required — the Ford doing recipe
 *   taskTypes: [info, coding]            # active chain templates (default: all four)
 *   features:
 *     interpret: { recipe: benjy-interpret }   # required — LightLm profile
 *     route:     { recipe: benjy-route }      # optional — off = mechanical fallback
 *     check:     { command: "python3 -m pytest -q" }  # optional — off = no mech verify
 *     evaluate:  { recipe: benjy-evaluate }   # optional — off = no per-item LLM eval
 *     reflect:   { recipe: benjy-reflect }    # optional — off = DONE on empty queue
 *     escalation: { recipe: coding }          # optional — off = escalate ends BLOCKED
 *   criteriaSources:                       # optional — interpret injects these docs
 *     - { ref: "requirements/abnahme.yaml" }
 * </pre>
 *
 * <p>Unknown feature names, unknown task types or a missing interpret /
 * do recipe fail at first use with a clear message — a broken recipe must
 * surface at the spawn turn, not mid-run.
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class BenjyFeatureConfig {

    public static final String TASK_TYPE_INFO = "info";
    public static final String TASK_TYPE_CODING = "coding";
    public static final String TASK_TYPE_PLANNING = "planning";
    public static final String TASK_TYPE_ANALYSIS = "analysis";

    public static final Set<String> TASK_TYPES =
            Set.of(TASK_TYPE_INFO, TASK_TYPE_CODING, TASK_TYPE_PLANNING, TASK_TYPE_ANALYSIS);

    private static final Set<String> KNOWN_FEATURES =
            Set.of("interpret", "route", "check", "evaluate", "reflect", "escalation");

    private final String interpretRecipe;
    private final @Nullable String routeRecipe;
    private final @Nullable String checkCommand;
    private final @Nullable String evaluateRecipe;
    private final @Nullable String reflectRecipe;
    private final @Nullable String escalationRecipe;
    private final String doRecipe;
    private final Set<String> taskTypes;
    private final List<String> criteriaSources;

    /**
     * Parses and validates the feature config from the effective engine
     * params. Throws {@link IllegalArgumentException} with a caller-ready
     * message on any misconfiguration (fail-fast, §4d limit 3).
     */
    public static BenjyFeatureConfig fromParams(Map<String, Object> params, String processId) {
        Map<String, Object> features = mapParam(params.get("features"));
        for (Object key : features.keySet()) {
            if (!KNOWN_FEATURES.contains(String.valueOf(key))) {
                throw new IllegalArgumentException("Benjy id='" + processId + "' params.features." + key
                        + " is not a known feature (known: " + KNOWN_FEATURES + ")");
            }
        }
        String interpret = featureRecipe(features, "interpret", processId);
        if (interpret == null) {
            throw new IllegalArgumentException("Benjy id='" + processId + "' params.features.interpret is required — "
                    + "every Benjy recipe needs the interpret LightLm profile");
        }
        String doRecipe = stringParam(params.get("doRecipe"));
        if (doRecipe == null) {
            throw new IllegalArgumentException("Benjy id='" + processId + "' params.doRecipe is required — "
                    + "Benjy delegates every item to a focused worker recipe");
        }
        Set<String> taskTypes = new LinkedHashSet<>();
        Object rawTypes = params.get("taskTypes");
        if (rawTypes instanceof List<?> list) {
            for (Object t : list) {
                String s = String.valueOf(t).toLowerCase(Locale.ROOT);
                if (!TASK_TYPES.contains(s)) {
                    throw new IllegalArgumentException("Benjy id='" + processId
                            + "' params.taskTypes contains unknown '" + s + "' (known: " + TASK_TYPES + ")");
                }
                taskTypes.add(s);
            }
        }
        if (taskTypes.isEmpty()) {
            taskTypes.addAll(TASK_TYPES);
        }
        List<String> sources = new java.util.ArrayList<>();
        Object rawSources = params.get("criteriaSources");
        if (rawSources instanceof List<?> list) {
            for (Object s : list) {
                if (s instanceof Map<?, ?> m && m.get("ref") instanceof String ref && !ref.isBlank()) {
                    sources.add(ref);
                } else if (s instanceof String str && !str.isBlank()) {
                    sources.add(str);
                } else {
                    throw new IllegalArgumentException(
                            "Benjy id='" + processId + "' params.criteriaSources entries must be "
                                    + "{ ref: \"<doc path>\" } or a plain path string");
                }
            }
        }
        return new BenjyFeatureConfig(
                interpret,
                featureRecipe(features, "route", processId),
                featureString(features, "check", "command", processId),
                featureRecipe(features, "evaluate", processId),
                featureRecipe(features, "reflect", processId),
                featureRecipe(features, "escalation", processId),
                doRecipe,
                Set.copyOf(taskTypes),
                List.copyOf(sources));
    }

    private static @Nullable String featureRecipe(Map<String, Object> features, String name, String processId) {
        return featureString(features, name, "recipe", processId);
    }

    private static @Nullable String featureString(
            Map<String, Object> features, String name, String key, String processId) {
        Object raw = features.get(name);
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b && !b) {
            // explicit "feature: false" — off by design
            return null;
        }
        if (raw instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
            throw new IllegalArgumentException("Benjy id='" + processId + "' params.features." + name + "." + key
                    + " must be a non-blank string when the feature is on");
        }
        throw new IllegalArgumentException("Benjy id='" + processId + "' params.features." + name + " must be a map ({"
                + key + ": \"…\"}) or false");
    }

    private static Map<String, Object> mapParam(@Nullable Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            return typed;
        }
        throw new IllegalArgumentException(
                "params.features must be a map, got " + raw.getClass().getSimpleName());
    }

    private static @Nullable String stringParam(@Nullable Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * The chain template for a task type (§4c): which verification stages
     * apply per item. Stages whose feature is off drop out of the chain —
     * the chain never references a disabled stage.
     */
    public List<String> chainFor(String taskType) {
        val chain = new java.util.ArrayList<String>();
        chain.add(BenjyTaskTypes.DO);
        if (TASK_TYPE_CODING.equals(taskType) && checkCommand != null) {
            chain.add(BenjyTaskTypes.CHECK);
        }
        if (!TASK_TYPE_INFO.equals(taskType) && evaluateRecipe != null) {
            chain.add(BenjyTaskTypes.EVALUATE);
        }
        chain.add(BenjyTaskTypes.CLOSE_ITEM);
        return chain;
    }

    public boolean isTaskTypeAllowed(String taskType) {
        return taskTypes.contains(taskType);
    }
}
