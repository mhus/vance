package de.mhus.vance.brain.zaphod;

import de.mhus.vance.api.zaphod.ZaphodPattern;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.Yaml;

/**
 * Pure-logic parser + validator for Zaphod council recipes. Mirrors
 * the contract of
 * {@link de.mhus.vance.brain.vogon.StrategyResolver#parseStrategy}
 * but for Zaphod's {@code engine: zaphod} recipe shape:
 *
 * <pre>
 * name: my-council
 * description: ...
 * engine: zaphod
 * params:
 *   pattern: COUNCIL
 *   heads:
 *     - name: optimist
 *       recipe: ford
 *       persona: Always argues for the upside.
 *     - name: pessimist
 *       recipe: ford
 *       persona: Always argues for the downside.
 *   synthesisPrompt: |
 *     Synthesize the heads' perspectives into one answer.
 * </pre>
 *
 * <p>Used by Slartibartfast's {@code ValidatingPhase} to reject
 * malformed Zaphod recipes before they reach EXECUTING — same role
 * Vogon's {@code StrategyResolver.parseStrategy} plays for
 * {@code VOGON_STRATEGY} drafts. The validation rules are kept in
 * sync with {@link ZaphodEngine#buildInitialState} so a recipe
 * that passes this parser will also pass the engine's own start-up
 * checks.
 *
 * <p>Throws {@link IllegalStateException} with a {@code pathHint}-
 * prefixed message on any violation; returns a {@link Spec} record
 * on success (the engine does its own state construction at spawn
 * time, so this result is primarily for callers that want to peek
 * at the council shape without round-tripping through MongoDB).
 */
public final class ZaphodHeadsParser {

    /** Soft cap mirrored from {@link ZaphodEngine#MAX_HEADS}.
     *  Recipes exceeding this fail the parse rather than getting
     *  silently truncated at engine-spawn time — Slart should
     *  learn from the recovery hint, not ship a too-large council. */
    public static final int MAX_HEADS = ZaphodEngine.MAX_HEADS;

    private ZaphodHeadsParser() {}

    /** Successful parse result. */
    public record Spec(
            String name,
            @Nullable String description,
            ZaphodPattern pattern,
            List<HeadSpec> heads,
            @Nullable String synthesisPrompt) {}

    /** One head entry inside {@link Spec#heads}. */
    public record HeadSpec(
            String name,
            String recipe,
            @Nullable String persona) {}

    /**
     * Parse + validate the YAML content of a Zaphod recipe document.
     * The {@code pathHint} is prefixed onto every error message so
     * callers can tell which document the failure refers to.
     *
     * @throws IllegalStateException on any shape, type or content
     *         violation. The message names the offending field path.
     */
    public static Spec parseRecipe(String yamlContent, String pathHint) {
        Object parsed = new Yaml().load(yamlContent);
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IllegalStateException(
                    pathHint + ": top-level YAML must be a map");
        }
        Map<String, Object> root = toStringMap(m);

        String name = requireString(root, "name", pathHint);
        String description = optString(root.get("description"));

        Object engineRaw = root.get("engine");
        if (engineRaw == null) {
            throw new IllegalStateException(
                    pathHint + ": recipe missing 'engine' field");
        }
        if (!"zaphod".equalsIgnoreCase(String.valueOf(engineRaw).trim())) {
            throw new IllegalStateException(
                    pathHint + ": recipe engine='" + engineRaw
                            + "', expected 'zaphod'");
        }

        Object paramsRaw = root.get("params");
        if (!(paramsRaw instanceof Map<?, ?> paramsMap)) {
            throw new IllegalStateException(
                    pathHint + ": recipe '" + name
                            + "' must declare a 'params:' map");
        }
        Map<String, Object> params = toStringMap(paramsMap);

        // pattern: required, COUNCIL only (V1).
        Object patternRaw = params.get("pattern");
        if (patternRaw == null) {
            throw new IllegalStateException(
                    pathHint + ": params.pattern missing — "
                            + "Zaphod V1 requires 'COUNCIL'");
        }
        ZaphodPattern pattern;
        try {
            pattern = ZaphodPattern.valueOf(
                    String.valueOf(patternRaw).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    pathHint + ": params.pattern='" + patternRaw
                            + "' is not a known ZaphodPattern");
        }
        if (pattern != ZaphodPattern.COUNCIL) {
            throw new IllegalStateException(
                    pathHint + ": Zaphod V1 supports only COUNCIL "
                            + "pattern; got " + pattern);
        }

        // heads: required, non-empty, ≤ MAX_HEADS, unique names.
        Object headsRaw = params.get("heads");
        if (!(headsRaw instanceof List<?> headList) || headList.isEmpty()) {
            throw new IllegalStateException(
                    pathHint + ": params.heads must be a non-empty list");
        }
        if (headList.size() > MAX_HEADS) {
            throw new IllegalStateException(
                    pathHint + ": params.heads has " + headList.size()
                            + " entries; the soft cap is " + MAX_HEADS
                            + " — split into multiple councils or drop "
                            + "less-distinct perspectives");
        }
        List<HeadSpec> heads = new ArrayList<>(headList.size());
        Set<String> seenNames = new LinkedHashSet<>();
        for (int i = 0; i < headList.size(); i++) {
            Object entry = headList.get(i);
            if (!(entry instanceof Map<?, ?> headMap)) {
                throw new IllegalStateException(
                        pathHint + ": params.heads[" + i + "] is not a map");
            }
            Map<String, Object> head = toStringMap(headMap);
            String headName = requireString(head, "name",
                    pathHint + ": params.heads[" + i + "].name");
            String headRecipe = requireString(head, "recipe",
                    pathHint + ": params.heads[" + i + "].recipe");
            if (!seenNames.add(headName)) {
                throw new IllegalStateException(
                        pathHint + ": params.heads has duplicate name '"
                                + headName + "' — head names must be "
                                + "unique within a council");
            }
            String persona = optString(head.get("persona"));
            heads.add(new HeadSpec(headName, headRecipe, persona));
        }

        // synthesisPrompt: optional but, when present, non-blank
        // (a blank prompt would defeat the synthesis turn).
        String synthesisPrompt = optString(params.get("synthesisPrompt"));

        return new Spec(name, description, pattern, heads, synthesisPrompt);
    }

    // ──────────────────── helpers ────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toStringMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>(m.size());
        for (Map.Entry<?, ?> e : m.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static String requireString(
            Map<String, Object> map, String key, String pathHint) {
        Object v = map.get(key);
        if (v == null) {
            throw new IllegalStateException(
                    pathHint + ": missing required field '" + key + "'");
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            throw new IllegalStateException(
                    pathHint + ": field '" + key + "' is blank");
        }
        return s;
    }

    private static @Nullable String optString(@Nullable Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
