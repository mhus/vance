package de.mhus.vance.brain.magrathea;

import de.mhus.vance.shared.magrathea.MagratheaLoopBody;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The decision of a {@code for_each_task}, as a pure function over what the run has recorded: is this a new loop
 * or the next element of one that is running, which way does the run go, and which variables does it set.
 *
 * <p>The loop keeps its state as ordinary variables, written by the dispatcher from the {@link Step}:
 * {@code _loop_<state>_items} (the list, written once when the loop starts), {@code _loop_<state>_index} (the
 * element being handled), {@code _loop_<state>_done} and, for a loop that collects, one
 * {@code _loop_<state>_result_<i>} per element. Nothing is rewritten per element but the index and the result of
 * that element, so a long list does not make the journal grow with the square of its length.
 *
 * <p>The loop is <em>entered</em> from the state before it. Entered from a state of its body — the arrow back —
 * it is the next element; entered from anywhere else it starts afresh, also when an earlier loop was left half
 * way by an arrow out of the body.
 */
final class MagratheaLoop {

    /** Elements a loop handles when the state does not say. */
    static final int DEFAULT_MAX_ITEMS = 100;

    /** The most elements any loop may have, whatever the state says. */
    static final int HARD_MAX_ITEMS = 1000;

    /** Name the element is set under when the state has no {@code as:}. */
    static final String DEFAULT_ELEMENT = "item";

    private MagratheaLoop() {}

    /**
     * What the loop state does.
     *
     * @param outcome {@code each}, {@code done} or {@code failure}
     * @param output  the collected results when the loop ends and collects; otherwise {@code null}
     * @param vars    variables to set, in order
     * @param failure why the loop cannot run; set exactly when {@code outcome} is {@code failure}
     */
    record Step(
            String outcome,
            @Nullable Object output,
            Map<String, Object> vars,
            @Nullable String failure) {}

    static String key(String state, String part) {
        return "_loop_" + state + "_" + part;
    }

    /**
     * @param spec          the state's fields with placeholders resolved ({@code items}, {@code as},
     *                      {@code maxItems}, {@code collect})
     * @param vars          the run's variables
     * @param previousState the state before the loop state, or {@code null} at the start of the run
     * @param body          the loop's body, from {@link MagratheaLoopBody#of}
     * @param enclosing     the loop this one sits in the body of, from {@link MagratheaLoopBody#enclosingLoop}, or
     *                      {@code null}; when this loop ends, {@code index} and {@code count} are set back to that
     *                      loop's element
     */
    static Step step(
            String loopState,
            Map<String, Object> spec,
            Map<String, Object> vars,
            @Nullable String previousState,
            Set<String> body,
            @Nullable String enclosing) {
        Object items = vars.get(key(loopState, "items"));
        Object index = vars.get(key(loopState, "index"));
        boolean running = items instanceof List<?>
                && index instanceof Number
                && !Boolean.TRUE.equals(vars.get(key(loopState, "done")));
        boolean next = running && previousState != null && body.contains(previousState);
        return next
                ? advance(loopState, spec, vars, (List<?>) items, ((Number) index).intValue(), enclosing)
                : start(loopState, spec, vars, enclosing);
    }

    private static Step start(
            String loopState, Map<String, Object> spec, Map<String, Object> vars, @Nullable String enclosing) {
        Object raw = spec.get("items");
        if (!(raw instanceof List<?> list)) {
            return failed("for_each_task '" + loopState + "': 'items' is not a list ("
                    + (raw == null ? "nothing" : raw.getClass().getSimpleName()) + ")");
        }
        int max = Math.min(maxItems(spec.get("maxItems")), HARD_MAX_ITEMS);
        if (list.size() > max) {
            return failed("for_each_task '" + loopState + "': " + list.size() + " items, more than the allowed " + max);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key(loopState, "items"), list);
        out.put(key(loopState, "index"), 0);
        out.put(key(loopState, "done"), false);
        if (list.isEmpty()) {
            out.put(key(loopState, "done"), true);
            restoreEnclosing(vars, enclosing, out);
            return new Step(MagratheaLoopBody.OUTCOME_DONE, spec.containsKey("collect") ? List.of() : null, out, null);
        }
        element(spec, list, 0, out);
        return new Step(MagratheaLoopBody.OUTCOME_EACH, null, out, null);
    }

    private static Step advance(
            String loopState,
            Map<String, Object> spec,
            Map<String, Object> vars,
            List<?> items,
            int index,
            @Nullable String enclosing) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean collecting = spec.containsKey("collect");
        if (collecting) {
            out.put(key(loopState, "result_" + index), spec.get("collect"));
        }
        int next = index + 1;
        if (next < items.size()) {
            out.put(key(loopState, "index"), next);
            element(spec, items, next, out);
            return new Step(MagratheaLoopBody.OUTCOME_EACH, null, out, null);
        }
        out.put(key(loopState, "done"), true);
        restoreEnclosing(vars, enclosing, out);
        Object results = null;
        if (collecting) {
            List<Object> all = new ArrayList<>(items.size());
            for (int i = 0; i < index; i++) {
                all.add(vars.get(key(loopState, "result_" + i)));
            }
            all.add(spec.get("collect"));
            results = all;
        }
        return new Step(MagratheaLoopBody.OUTCOME_DONE, results, out, null);
    }

    /** The element, its place and the length of the list, set for the body. */
    private static void element(Map<String, Object> spec, List<?> items, int index, Map<String, Object> out) {
        Object as = spec.get("as");
        out.put(as instanceof String s && !s.isBlank() ? s : DEFAULT_ELEMENT, items.get(index));
        out.put("index", index);
        out.put("count", items.size());
    }

    /**
     * A loop inside the body of another overwrote {@code index} and {@code count} with its own; when it ends they
     * are the enclosing loop's again, so the rest of that body sees its own element's place.
     */
    private static void restoreEnclosing(
            Map<String, Object> vars, @Nullable String enclosing, Map<String, Object> out) {
        if (enclosing == null) return;
        Object index = vars.get(key(enclosing, "index"));
        Object items = vars.get(key(enclosing, "items"));
        if (index instanceof Number && items instanceof List<?> list) {
            out.put("index", index);
            out.put("count", list.size());
        }
    }

    private static int maxItems(@Nullable Object raw) {
        return raw instanceof Number n && n.intValue() > 0 ? n.intValue() : DEFAULT_MAX_ITEMS;
    }

    private static Step failed(String message) {
        return new Step(TaskCompletedEvent.OUTCOME_FAILURE, null, Map.of(), message);
    }
}
