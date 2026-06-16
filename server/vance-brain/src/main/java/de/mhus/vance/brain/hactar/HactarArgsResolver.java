package de.mhus.vance.brain.hactar;

import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Resolves the {@code vance.params.<name>} inputs a script expects
 * into a concrete {@code scriptParams} map for the executor.
 *
 * <p>Pipeline (called from
 * {@link de.mhus.vance.brain.hactar.phases.LoadingPhase} right after
 * the minimal-validate gate):
 *
 * <ol>
 *   <li>Regex-scan the script body for {@code vance.params.<name>}
 *       references. Property-access (dotted) only — bracket-notation
 *       ({@code vance.params['n']}) is intentionally not parsed (no
 *       reliable static analysis).</li>
 *   <li>Diff against the caller-supplied {@code scriptParams} map.
 *       Keys the caller already provided win — never override.</li>
 *   <li>For the remaining keys: when a free-text {@code intent} is
 *       available (typically {@code process.goal}), call
 *       {@link LightLlmService} with the bundled
 *       {@code hactar-args-extract} recipe to extract concrete
 *       values from the intent text. Merge the LLM output into the
 *       scriptParams.</li>
 *   <li>Any key the LLM left in {@code unresolved} (or all
 *       missing keys when no intent text exists at all) causes the
 *       resolver to throw {@link MissingParamException} — Hactar's
 *       LoadingPhase converts that into a FAILED status with a
 *       human-readable reason.</li>
 * </ol>
 *
 * <p>Why this layer exists: Hactar is the universal script-executor
 * — Scheduler/Event/Cortex/LLM-Tools all spawn it. Callers can't
 * always know what the script needs (Slart-generated scripts are
 * authored ad-hoc with whatever inputs the LLM thought were
 * useful). Auto-resolving missing params from the free-text intent
 * makes the Hactar surface forgiving without forcing each caller
 * to learn the script's contract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HactarArgsResolver {

    /** Recipe used by {@link LightLlmService}; lives at
     *  {@code _vance/recipes/hactar-args-extract.yaml}. */
    static final String EXTRACT_RECIPE_NAME = "hactar-args-extract";

    /**
     * Matches {@code vance.params.<identifier>} references. The
     * identifier rule mirrors JavaScript's: letter / underscore /
     * dollar, followed by alphanumeric / underscore / dollar.
     * Word-boundary at the end keeps it from matching prefixes
     * (e.g. {@code vance.params.foo} only — not the substring of
     * {@code vance.params.foobar}).
     */
    private static final Pattern PARAMS_REF = Pattern.compile(
            "\\bvance\\s*\\.\\s*params\\s*\\.\\s*"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\b");

    private final LightLlmService lightLlm;

    /**
     * Extract the {@code vance.params.<name>} identifiers a script
     * references. Returns a {@link LinkedHashSet} (preserves
     * scan order — useful for stable error messages and prompt
     * stability). Empty when the script doesn't read any params.
     */
    public Set<String> extractRequiredKeys(@Nullable String code) {
        Set<String> out = new LinkedHashSet<>();
        if (code == null || code.isBlank()) return out;
        Matcher m = PARAMS_REF.matcher(code);
        while (m.find()) {
            String key = m.group(1);
            // Defensive: ignore property accesses on standard JS
            // built-ins / methods that aren't real params (e.g.
            // `vance.params.hasOwnProperty` — that's a Map method,
            // not a script-input). Hactar's params binding is an
            // immutable view, so only a few are reachable; this
            // keeps the LLM-extract prompt clean.
            if (RESERVED_MAP_METHODS.contains(key)) continue;
            out.add(key);
        }
        return out;
    }

    /**
     * Resolve the final {@code scriptParams} map by merging
     * caller-supplied values with LLM-extracted values from the
     * intent text.
     *
     * @param code       full script body (used for the LLM prompt)
     * @param supplied   caller-supplied scriptParams; never modified.
     *                   Pass empty map (not null) when none.
     * @param intent     free-text user intent — typically
     *                   {@code process.goal} on the spawn document.
     *                   When blank, the resolver only verifies that
     *                   {@code supplied} covers the required keys;
     *                   no LLM call happens.
     * @param tenantId   tenant scope for {@link LightLlmService}
     * @param projectId  project scope (nullable)
     * @param processId  caller process id for setting-cascade
     * @throws MissingParamException when one or more required keys
     *         couldn't be resolved (either no intent provided, or
     *         the LLM left them in {@code unresolved}).
     */
    public Map<String, Object> resolve(
            String code,
            Map<String, Object> supplied,
            @Nullable String intent,
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId) {

        Set<String> required = extractRequiredKeys(code);
        if (required.isEmpty()) {
            // Script reads no vance.params.* — pass supplied through
            // (callers may still wire other shape, e.g. top-level
            // args via bindings; ExecutingPhase keeps that working).
            return supplied == null ? Map.of() : Map.copyOf(supplied);
        }

        Set<String> supplied_ = supplied == null ? Set.of() : supplied.keySet();
        List<String> missing = new ArrayList<>();
        for (String k : required) {
            if (!supplied_.contains(k)) missing.add(k);
        }
        if (missing.isEmpty()) {
            return Map.copyOf(supplied);
        }

        // Anything still missing — try LLM extraction from intent.
        if (intent == null || intent.isBlank()) {
            log.warn("HactarArgsResolver: script needs {} but caller "
                            + "supplied only {} and no intent text — "
                            + "throwing MissingParamException",
                    required, supplied_);
            throw new MissingParamException(required, supplied_, missing,
                    "No process.goal / intent text available to extract "
                            + "the missing parameters from.");
        }

        Map<String, Object> pebbleVars = new LinkedHashMap<>();
        pebbleVars.put("code", code);
        pebbleVars.put("intent", intent);
        pebbleVars.put("requiredKeys", String.join(", ", missing));
        if (!supplied_.isEmpty()) {
            pebbleVars.put("suppliedKeys", String.join(", ", supplied_));
        }

        Map<String, Object> reply;
        try {
            reply = lightLlm.callForJson(LightLlmRequest.builder()
                    .recipeName(EXTRACT_RECIPE_NAME)
                    .userPrompt("Extract missing script parameters.")
                    .pebbleVars(pebbleVars)
                    .schema(EXTRACT_SCHEMA)
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .processId(processId)
                    .build());
        } catch (RuntimeException e) {
            log.warn("HactarArgsResolver: LightLlm call failed: {}", e.toString());
            throw new MissingParamException(required, supplied_, missing,
                    "LightLlm extraction failed: " + e.getMessage());
        }

        Map<String, Object> extracted = readMap(reply.get("params"));
        List<String> unresolved = readStringList(reply.get("unresolved"));

        // Merge: caller-supplied wins over LLM-extracted; LLM fills
        // only the gaps.
        Map<String, Object> merged = new LinkedHashMap<>();
        if (supplied != null) merged.putAll(supplied);
        for (Map.Entry<String, Object> e : extracted.entrySet()) {
            if (!merged.containsKey(e.getKey())) {
                merged.put(e.getKey(), e.getValue());
            }
        }

        // What remains unresolved? Anything in `missing` that's not
        // in the merged map. Use the LLM's explicit unresolved list
        // as a hint but trust the merged-map state as authoritative.
        List<String> stillMissing = new ArrayList<>();
        for (String k : missing) {
            if (!merged.containsKey(k)) stillMissing.add(k);
        }
        if (!stillMissing.isEmpty()) {
            log.warn("HactarArgsResolver: LLM left {} unresolved "
                            + "(reported unresolved={}); throwing.",
                    stillMissing, unresolved);
            throw new MissingParamException(required, merged.keySet(),
                    stillMissing,
                    unresolved.isEmpty()
                            ? "LLM extraction returned no value for these keys."
                            : "LLM explicitly marked these as unresolved: "
                                    + unresolved);
        }

        log.info("HactarArgsResolver: resolved {} param(s) from intent "
                        + "(caller-supplied={}, llm-extracted={})",
                required.size(), supplied_.size(), extracted.size());
        return java.util.Collections.unmodifiableMap(merged);
    }

    // ──────────────────── Helpers ────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(@Nullable Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static List<String> readStringList(@Nullable Object raw) {
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) out.add(s);
            }
            return out;
        }
        return List.of();
    }

    /** Permissive top-level schema; the recipe prompt enforces the
     *  actual shape via its JSON contract. */
    private static final Map<String, Object> EXTRACT_SCHEMA = Map.of(
            "type", "object");

    /**
     * JavaScript built-in property names that aren't real script
     * params. Filtered out of the required-keys set so the LLM
     * doesn't waste tokens explaining {@code hasOwnProperty} or
     * {@code toString}.
     */
    private static final Set<String> RESERVED_MAP_METHODS = Set.of(
            "hasOwnProperty", "toString", "valueOf", "constructor",
            "isPrototypeOf", "propertyIsEnumerable", "toLocaleString",
            "__proto__");

    /**
     * Thrown when one or more required script parameters could not
     * be resolved. {@link de.mhus.vance.brain.hactar.phases.LoadingPhase}
     * catches this and maps it to a FAILED Hactar status with a
     * human-readable {@code failureReason}.
     */
    public static final class MissingParamException extends RuntimeException {
        private final Set<String> required;
        private final Set<String> available;
        private final List<String> missing;

        public MissingParamException(
                Set<String> required, Set<String> available,
                List<String> missing, String detail) {
            super("Hactar args resolution failed — missing "
                    + missing + " (required " + required
                    + ", available " + available + "). " + detail);
            this.required = Set.copyOf(required);
            this.available = Set.copyOf(available);
            this.missing = List.copyOf(missing);
        }

        public Set<String> required() { return required; }
        public Set<String> available() { return available; }
        public List<String> missing() { return missing; }
    }
}
