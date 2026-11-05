package de.mhus.vance.brain.magrathea;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** A string that is exactly one placeholder and nothing else around it. */
    private static final Pattern WHOLE_PLACEHOLDER = Pattern.compile("\\s*\\$\\{([^}]+)\\}\\s*");

    private final Map<String, Object> params;
    private final Map<String, Object> state;

    MagratheaSubstitutor(@Nullable Map<String, Object> params, @Nullable Map<String, Object> state) {
        this.params = params == null ? Map.of() : params;
        this.state = state == null ? Map.of() : state;
    }

    /** Deep-substitutes every String value in the spec map; every placeholder becomes text. */
    Map<String, Object> substituteSpec(@Nullable Map<String, Object> spec) {
        return substituteSpec(spec, Set.of());
    }

    /**
     * Deep-substitutes the spec. Under the keys in {@code typedKeys} a value that is
     * exactly one placeholder ({@code "${state.list}"}) keeps its type — a list stays a
     * list, an object an object — instead of becoming text. Those keys carry data for
     * something that takes structure (a tool's arguments, a sub-workflow's parameters,
     * the items of a for-each); everywhere else a placeholder is text, because the
     * consumers there (prompts, titles, bodies) read strings.
     */
    Map<String, Object> substituteSpec(@Nullable Map<String, Object> spec, Set<String> typedKeys) {
        if (spec == null || spec.isEmpty()) return spec == null ? Map.of() : spec;
        Map<String, Object> out = new LinkedHashMap<>(spec.size());
        for (Map.Entry<String, Object> e : spec.entrySet()) {
            out.put(e.getKey(), substituteValue(e.getValue(), typedKeys.contains(e.getKey())));
        }
        return out;
    }

    private @Nullable Object substituteValue(@Nullable Object value, boolean typed) {
        if (value instanceof String s) {
            if (typed) {
                Matcher whole = WHOLE_PLACEHOLDER.matcher(s);
                if (whole.matches()) {
                    // Missing resolves to "", as in text — a blank, not a crash.
                    Object found = lookup(whole.group(1).trim());
                    return found == null ? "" : found;
                }
            }
            return apply(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), substituteValue(e.getValue(), typed));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(substituteValue(item, typed));
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
            Object found = lookup(m.group(1).trim());
            m.appendReplacement(sb, Matcher.quoteReplacement(found == null ? "" : String.valueOf(found)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Resolves a reference like {@code params.pr.url}, {@code state.summary} or
     * {@code state.items[0].name} to the value itself, or {@code null} when any step of
     * the way is missing. Indexes are fixed numbers; they apply to lists only.
     */
    private @Nullable Object lookup(String ref) {
        List<Object> path = parsePath(ref);
        if (path == null || path.size() < 2 || !(path.get(0) instanceof String root)) return null;
        Object cursor =
                switch (root) {
                    case "params" -> path.get(1) instanceof String key ? params.get(key) : null;
                    case "state" -> path.get(1) instanceof String key ? state.get(key) : null;
                    default -> null;
                };
        for (int i = 2; i < path.size() && cursor != null; i++) {
            Object step = path.get(i);
            if (step instanceof Integer index) {
                cursor = cursor instanceof List<?> list && index >= 0 && index < list.size() ? list.get(index) : null;
            } else if (cursor instanceof Map<?, ?> map) {
                cursor = map.get(step);
            } else {
                return null;
            }
        }
        return cursor;
    }

    /**
     * Splits {@code a.b[0][1].c} into {@code a}, {@code b}, 0, 1, {@code c} — names as
     * strings, indexes as integers. {@code null} for a reference that is not of that
     * shape (an empty segment, an unclosed bracket, a non-numeric index).
     */
    private static @Nullable List<Object> parsePath(String ref) {
        List<Object> out = new ArrayList<>();
        for (String segment : ref.split("\\.", -1)) {
            if (segment.isEmpty()) return null;
            int bracket = segment.indexOf('[');
            String name = bracket < 0 ? segment : segment.substring(0, bracket);
            if (name.isEmpty()) return null;
            out.add(name);
            while (bracket >= 0) {
                int close = segment.indexOf(']', bracket);
                if (close < 0) return null;
                try {
                    out.add(Integer.parseInt(segment.substring(bracket + 1, close)));
                } catch (NumberFormatException ex) {
                    return null;
                }
                bracket = segment.indexOf('[', close);
                if (bracket >= 0 && bracket != close + 1) return null;
            }
            if (bracket < 0 && segment.indexOf(']') >= 0 && !segment.endsWith("]")) return null;
        }
        return out;
    }
}
