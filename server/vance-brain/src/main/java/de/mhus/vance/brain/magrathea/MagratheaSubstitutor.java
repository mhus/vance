package de.mhus.vance.brain.magrathea;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Resolves {@code ${params.X[.Y…]}} and {@code ${state.X[.Y…]}}
 * placeholders in a Magrathea task spec before the type-executor runs
 * (code-review Phase 2). The dataflow between workflow tasks — a task
 * reading {@code ${state.review_summary}} produced by an earlier task's
 * {@code storeAs} — was documented in every executor's javadoc but never
 * implemented; placeholders leaked verbatim.
 *
 * <ul>
 *   <li>{@code params.*} → the run's resolved params
 *       ({@code StartRecord.params}).</li>
 *   <li>{@code state.*} → the accumulated var map
 *       ({@code projectVars}, filled from each task's {@code storeAs}).</li>
 * </ul>
 *
 * <p>Missing keys resolve to an empty string (mirrors {@code
 * VogonSubstitutor}) — a workflow author sees a blank, not a crash. Values
 * without a {@code ${…}} pattern (e.g. a SpEL gate expression
 * {@code #state.x > 5}) pass through untouched.
 */
final class MagratheaSubstitutor {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)\\}");

    private final Map<String, Object> params;
    private final Map<String, Object> state;

    MagratheaSubstitutor(@Nullable Map<String, Object> params, @Nullable Map<String, Object> state) {
        this.params = params == null ? Map.of() : params;
        this.state = state == null ? Map.of() : state;
    }

    /** Deep-substitutes every String value in the spec map. */
    @SuppressWarnings("unchecked")
    Map<String, Object> substituteSpec(@Nullable Map<String, Object> spec) {
        if (spec == null || spec.isEmpty()) return spec == null ? Map.of() : spec;
        Map<String, Object> out = new LinkedHashMap<>(spec.size());
        for (Map.Entry<String, Object> e : spec.entrySet()) {
            out.put(e.getKey(), substituteValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private @Nullable Object substituteValue(@Nullable Object value) {
        if (value instanceof String s) {
            return apply(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), substituteValue(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(substituteValue(item));
            }
            return out;
        }
        return value;
    }

    /** Replaces every {@code ${…}} in {@code template}. */
    String apply(String template) {
        if (template == null || template.indexOf("${") < 0) return template;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String resolved = resolve(m.group(1).trim());
            m.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Resolves a dotted reference like {@code params.pr.url} / {@code state.summary}. */
    private String resolve(String ref) {
        String[] parts = ref.split("\\.");
        if (parts.length < 2) return "";
        Object cursor = switch (parts[0]) {
            case "params" -> params.get(parts[1]);
            case "state" -> state.get(parts[1]);
            default -> null;
        };
        for (int i = 2; i < parts.length && cursor != null; i++) {
            if (cursor instanceof Map<?, ?> map) {
                cursor = map.get(parts[i]);
            } else {
                return "";
            }
        }
        return cursor == null ? "" : String.valueOf(cursor);
    }
}
