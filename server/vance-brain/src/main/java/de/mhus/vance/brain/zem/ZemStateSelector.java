package de.mhus.vance.brain.zem;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects a map state onto the listed field paths before the call.
 * Irrelevant context measurably lowers decision accuracy ("context rot"
 * in the TypeSafe docs), so a profile can name the fields that matter.
 *
 * <p>Paths are dot-separated ({@code customer.tier}); the projection
 * keeps the nesting. Missing paths are skipped silently — records in a
 * bulk run need not all carry every field. A string or list state passes
 * through unchanged: there is nothing to select from.
 */
final class ZemStateSelector {

    private ZemStateSelector() {}

    static Object select(Object state, List<String> paths) {
        if (paths.isEmpty() || !(state instanceof Map<?, ?> source)) {
            return state;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String path : paths) {
            copy(source, path.split("\\."), 0, out);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void copy(Map<?, ?> source, String[] segments, int index, Map<String, Object> target) {
        Object value = source.get(segments[index]);
        if (value == null && !source.containsKey(segments[index])) {
            return;
        }
        if (index == segments.length - 1) {
            target.put(segments[index], value);
            return;
        }
        if (!(value instanceof Map<?, ?> child)) {
            return;
        }
        Object existing = target.get(segments[index]);
        Map<String, Object> nested;
        if (existing instanceof Map<?, ?> map) {
            nested = (Map<String, Object>) map;
        } else {
            nested = new LinkedHashMap<>();
            target.put(segments[index], nested);
        }
        copy(child, segments, index + 1, nested);
    }
}
