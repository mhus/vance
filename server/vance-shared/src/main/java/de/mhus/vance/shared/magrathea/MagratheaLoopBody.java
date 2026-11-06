package de.mhus.vance.shared.magrathea;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The body of a {@code for_each_task}: the states a pass over one element goes through. It is not declared
 * anywhere — it follows from the arrows. The body is every state that can be reached from the state's
 * {@code each} outcome without passing the loop state itself; the arrow back to the loop state ends a pass.
 *
 * <p>The engine uses it to tell "the next element" (the loop state was entered from a state of its body) from
 * "a new loop" (entered from anywhere else), and the editor uses it to draw the frame around the body. A state
 * that leaves the body by an arrow to somewhere else is a {@code break}: that state is still counted as body,
 * but what it leads to is not.
 */
public final class MagratheaLoopBody {

    /** The outcome that leads into the body, once per element. */
    public static final String OUTCOME_EACH = "each";

    /** The outcome that leaves the loop, after the last element. */
    public static final String OUTCOME_DONE = "done";

    private MagratheaLoopBody() {}

    /**
     * The states of the body of {@code loopState}, in the order they are first reached; empty when the loop
     * state has no {@code each} arrow or none that is not the loop itself.
     */
    public static Set<String> of(Map<String, MagratheaStateSpec> states, String loopState) {
        MagratheaStateSpec loop = states.get(loopState);
        Set<String> body = new LinkedHashSet<>();
        if (loop == null) return body;
        @Nullable String entry = loop.onOutcomes().get(OUTCOME_EACH);
        if (entry == null || entry.equals(loopState)) return body;

        Deque<String> queue = new ArrayDeque<>();
        queue.add(entry);
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (name.equals(loopState) || !body.add(name)) continue;
            MagratheaStateSpec state = states.get(name);
            if (state == null) continue;
            state.onOutcomes().values().forEach(queue::add);
            state.catchKinds().values().forEach(queue::add);
            state.transitions().forEach(t -> queue.add(t.target()));
        }
        return body;
    }
}
