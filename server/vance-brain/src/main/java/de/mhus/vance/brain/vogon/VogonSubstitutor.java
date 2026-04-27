package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.vogon.StrategyState;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Resolves {@code ${…}} placeholders in strategy templates.
 * v1 supports three sources:
 *
 * <ul>
 *   <li>{@code ${params.X[.Y]}} — caller-supplied params merged
 *       with strategy paramDefaults.</li>
 *   <li>{@code ${state.X}} — strategyState.flags lookup.</li>
 *   <li>{@code ${phases.X.result}} — strategyState.phaseArtifacts
 *       lookup ({@code phaseArtifacts.<X>.result}).</li>
 * </ul>
 *
 * <p>Missing keys resolve to an empty string with a warning rather
 * than throwing — strategies should keep working in degenerate
 * cases. v2 may add cycle-detection / type-aware substitution.
 */
public final class VogonSubstitutor {

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([^}]+)\\}");

    private final Map<String, Object> params;
    private final StrategyState state;

    public VogonSubstitutor(Map<String, Object> params, StrategyState state) {
        this.params = params;
        this.state = state;
    }

    /** Substitute every {@code ${…}} occurrence in {@code template}. */
    public String apply(@Nullable String template) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1).trim();
            String value = resolve(key);
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String resolve(String key) {
        if (key.startsWith("params.")) {
            return stringOf(navigate(params, key.substring("params.".length())));
        }
        if (key.startsWith("state.")) {
            return stringOf(state.getFlags().get(key.substring("state.".length())));
        }
        if (key.startsWith("phases.")) {
            // ${phases.<phase>.<artifactKey>}
            String rest = key.substring("phases.".length());
            int dot = rest.indexOf('.');
            if (dot < 0) return "";
            String phase = rest.substring(0, dot);
            String artifactKey = rest.substring(dot + 1);
            Map<String, Object> phaseArtifacts = state.getPhaseArtifacts().get(phase);
            if (phaseArtifacts == null) return "";
            return stringOf(navigate(phaseArtifacts, artifactKey));
        }
        return "";
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
