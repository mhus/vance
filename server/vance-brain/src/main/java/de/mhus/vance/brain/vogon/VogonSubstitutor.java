package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.StrategyState;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Resolves {@code ${…}} placeholders in strategy templates.
 * Supported sources:
 *
 * <ul>
 *   <li>{@code ${params.X[.Y]}} — caller-supplied params merged
 *       with strategy paramDefaults.</li>
 *   <li>{@code ${state.X}} — strategyState.flags lookup (legacy
 *       alias for {@code flags.X}).</li>
 *   <li>{@code ${flags.X}} — strategyState.flags lookup.</li>
 *   <li>{@code ${phases.X.<artifactKey>}} — strategyState.phaseArtifacts
 *       lookup ({@code phaseArtifacts.<X>.<artifactKey>}). The shape
 *       {@code phases.X.artifacts.<key>} is also accepted for spec
 *       symmetry — it strips the {@code .artifacts} infix.</li>
 *   <li>{@code ${result.X}} — only in {@link #applyWithResult} /
 *       {@link #resolveTyped} when a {@code resultFields} map is
 *       provided (the spec's "result-scope" for
 *       {@code result.text}; see {@code specification/vogon-engine.md}
 *       §3.2).</li>
 * </ul>
 *
 * <p>Missing keys resolve to an empty string with no warning rather
 * than throwing — strategies should keep working in degenerate
 * cases. The exception is {@link #resolveTyped} which returns
 * {@code null} so the caller can distinguish "missing" from
 * "present but empty".
 */
public final class VogonSubstitutor {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([^}]+)\\}");

    /**
     * A template that consists of exactly one {@code ${…}} placeholder
     * — used by {@link #resolveTyped} to detect when the source value
     * should be returned verbatim instead of coerced to a string.
     * Trailing/leading whitespace allowed.
     */
    private static final Pattern SINGLE_REF =
            Pattern.compile("^\\s*\\$\\{([^}]+)\\}\\s*$");

    private final Map<String, Object> params;
    private final StrategyState state;

    public VogonSubstitutor(Map<String, Object> params, StrategyState state) {
        this.params = params;
        this.state = state;
    }

    /** Substitute every {@code ${…}} occurrence in {@code template}. */
    public String apply(@Nullable String template) {
        return applyWithResult(template, null);
    }

    /**
     * String-rendering variant with an additional {@code result.X}
     * scope. Used to render {@code result.text} after
     * {@code result.fields} has been evaluated — the field map is
     * passed as {@code resultFields} and referenced via
     * {@code ${result.X}}.
     */
    public String applyWithResult(
            @Nullable String template, @Nullable Map<String, Object> resultFields) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            Object resolved = resolveAny(key, resultFields);
            String value = stringOf(resolved);
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Type-preserving variant. When {@code template} is exactly one
     * {@code ${…}} placeholder (with optional whitespace), returns
     * the resolved value verbatim — preserving its source type
     * ({@code Number}, {@code Boolean}, {@code List}, {@code Map},
     * {@code null} when the path doesn't exist).
     *
     * <p>When {@code template} is plain text or interpolated
     * ({@code "${params.x} - ${flags.y}"}), the result is rendered
     * as a {@code String} (same as {@link #applyWithResult}).
     *
     * <p>Used by {@code ResultEvaluator} to build the structured
     * {@code result.fields} payload that lands on the parent's
     * {@code SteerMessage.Reply.payload}.
     */
    public @Nullable Object resolveTyped(
            @Nullable String template, @Nullable Map<String, Object> resultFields) {
        if (template == null) return null;
        Matcher single = SINGLE_REF.matcher(template);
        if (single.matches()) {
            String key = single.group(1).trim();
            return resolveAny(key, resultFields);
        }
        return applyWithResult(template, resultFields);
    }

    private @Nullable Object resolveAny(
            String key, @Nullable Map<String, Object> resultFields) {
        if (key.startsWith("params.")) {
            return navigate(params, key.substring("params.".length()));
        }
        if (key.startsWith("state.")) {
            return state.getFlags().get(key.substring("state.".length()));
        }
        if (key.startsWith("flags.")) {
            return navigate(state.getFlags(), key.substring("flags.".length()));
        }
        if (key.startsWith("phases.")) {
            // ${phases.<phase>.<artifactKey>} OR
            // ${phases.<phase>.artifacts.<artifactKey>} (spec form,
            // §3.2 example uses .artifacts. infix; strip it for the
            // map-of-phaseArtifacts lookup).
            String rest = key.substring("phases.".length());
            int dot = rest.indexOf('.');
            if (dot < 0) return null;
            String phase = rest.substring(0, dot);
            String artifactKey = rest.substring(dot + 1);
            if (artifactKey.startsWith("artifacts.")) {
                artifactKey = artifactKey.substring("artifacts.".length());
            }
            Map<String, Object> phaseArtifacts = state.getPhaseArtifacts().get(phase);
            if (phaseArtifacts == null) return null;
            return navigate(phaseArtifacts, artifactKey);
        }
        if (key.startsWith("result.") && resultFields != null) {
            return navigate(resultFields, key.substring("result.".length()));
        }
        return null;
    }

    /** Walk a dotted path through nested maps. Returns the final
     *  value or null if any segment is missing. */
    @SuppressWarnings("unchecked")
    private static @Nullable Object navigate(@Nullable Map<String, Object> root, String path) {
        if (root == null) return null;
        String[] segments = path.split("\\.");
        Object current = root;
        for (String seg : segments) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = ((Map<String, Object>) m).get(seg);
            if (current == null) return null;
        }
        return current;
    }

    private static String stringOf(@Nullable Object value) {
        if (value == null) return "";
        return value.toString();
    }
}
