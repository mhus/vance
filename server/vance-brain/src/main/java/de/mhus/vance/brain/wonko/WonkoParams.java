package de.mhus.vance.brain.wonko;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Typed view on the process's engine params, with Wonko's defaults. */
record WonkoParams(Map<String, Object> raw) {

    String checkRecipe() {
        return string("checkRecipe", "wonko-check");
    }

    String interpretRecipe() {
        return string("interpretRecipe", "wonko-interpret");
    }

    String routeRecipe() {
        return string("routeRecipe", "wonko-route");
    }

    /** Recipe of the worker the router's {@code work} starts — a plain tool loop by default. */
    String workRecipe() {
        return string("workRecipe", "frankie");
    }

    String decomposeRecipe() {
        return string("decomposeRecipe", "wonko-decompose");
    }

    String locateRecipe() {
        return string("locateRecipe", "wonko-locate");
    }

    String compareRecipe() {
        return string("compareRecipe", "wonko-compare");
    }

    String coverageRecipe() {
        return string("coverageRecipe", "wonko-coverage");
    }

    int maxRounds() {
        return integer("maxRounds", 6);
    }

    int maxQuestionsPerRound() {
        return integer("maxQuestionsPerRound", 3);
    }

    int maxClaims() {
        return integer("maxClaims", 25);
    }

    int maxPassagesPerClaim() {
        return integer("maxPassagesPerClaim", 4);
    }

    int maxCandidates() {
        return integer("maxCandidates", 12);
    }

    /** How many cross-reference hops {@code LOCATE} follows. */
    int maxLocateDepth() {
        return integer("maxLocateDepth", 1);
    }

    /** Model calls per run before the budget checkpoint asks to go on. */
    int maxCalls() {
        return integer("maxCalls", 300);
    }

    /** Compare calls running at the same time. */
    int parallelism() {
        return integer("parallelism", 4);
    }

    /**
     * Skill selectors for every model call: names, {@code @label} and
     * {@code !}-exclusions — the {@code relevantSkills} grammar.
     */
    List<String> skills() {
        Object v = raw.get("skills");
        if (v instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return v instanceof String s && !s.isBlank() ? List.of(s.trim()) : List.of();
    }

    boolean coverage() {
        Object v = raw.get("coverage");
        return !(v instanceof Boolean b) || b;
    }

    private String string(String key, String fallback) {
        Object v = raw.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private int integer(String key, int fallback) {
        Object v = raw.get(key);
        if (v instanceof Number n) {
            return Math.max(1, n.intValue());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    @Nullable
    Object get(String key) {
        return raw.get(key);
    }
}
