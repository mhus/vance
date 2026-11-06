package de.mhus.vance.shared.magrathea;

import de.mhus.vance.api.magrathea.MagratheaTaskType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * The loop whose body the state {@code name} is part of — the one an {@code end} state ends. When the state is
     * in the bodies of several loops (a loop inside a loop: the inner body is part of the outer one) it is the
     * innermost: the one with the smallest body, which has to lie wholly inside every other.
     *
     * @return the loop state, or empty when the state is in no loop body
     * @throws IllegalStateException when the state is in the bodies of loops that do not lie one inside the other
     */
    public static Optional<String> enclosingLoop(Map<String, MagratheaStateSpec> states, String name) {
        List<Map.Entry<String, Set<String>>> holding = new ArrayList<>();
        for (MagratheaStateSpec state : states.values()) {
            if (state.type() != MagratheaTaskType.FOR_EACH_TASK) continue;
            Set<String> body = of(states, state.name());
            if (body.contains(name)) holding.add(Map.entry(state.name(), body));
        }
        if (holding.isEmpty()) return Optional.empty();
        holding.sort(Comparator.comparingInt(e -> e.getValue().size()));
        for (int i = 1; i < holding.size(); i++) {
            Map.Entry<String, Set<String>> inner = holding.get(i - 1);
            Map.Entry<String, Set<String>> outer = holding.get(i);
            if (inner.getValue().size() >= outer.getValue().size()
                    || !outer.getValue().containsAll(inner.getValue())) {
                throw new IllegalStateException("state '" + name + "' is in the bodies of the loops '" + inner.getKey()
                        + "' and '" + outer.getKey() + "', which do not lie one inside the other");
            }
        }
        return Optional.of(holding.get(0).getKey());
    }

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
