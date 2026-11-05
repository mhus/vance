package de.mhus.vance.shared.magrathea;

import de.mhus.vance.shared.magrathea.journal.StartRecord;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How a run is to be debugged from its first state on.
 *
 * <p>Two independent things. {@code stepping} halts the run before <em>every</em>
 * state. {@code breakpoints} halt it before the <em>named</em> states only and let
 * it run freely in between. Either one makes the run debuggable: it can be
 * stepped, resumed and — for breakpoints — re-armed while it runs. A run with
 * neither is an ordinary run and pays nothing for any of this.
 *
 * <p>{@code workflowBreakpoints} are not for this run's own states but for the
 * sub-runs it starts, by workflow name: a sub-run of workflow {@code W} takes the
 * entry for {@code W} as its breakpoints.
 *
 * @param stepping            halt before every state
 * @param breakpoints         states to halt before, in the order given
 * @param workflowBreakpoints breakpoints for sub-run workflows, by workflow name
 */
public record MagratheaDebugSetup(
        boolean stepping, Set<String> breakpoints, Map<String, Set<String>> workflowBreakpoints) {

    /** No debugging: an ordinary run. */
    public static final MagratheaDebugSetup OFF = new MagratheaDebugSetup(false, Set.of());

    public MagratheaDebugSetup {
        breakpoints = ordered(breakpoints);
        workflowBreakpoints = orderedByWorkflow(workflowBreakpoints);
    }

    /** A setup without breakpoints for sub-runs. */
    public MagratheaDebugSetup(boolean stepping, Set<String> breakpoints) {
        this(stepping, breakpoints, Map.of());
    }

    /** {@code true} when the run can be debugged at all. */
    public boolean active() {
        return stepping || !breakpoints.isEmpty() || !workflowBreakpoints.isEmpty();
    }

    /** The setup from request fields; a missing flag or list means "off". */
    public static MagratheaDebugSetup of(@Nullable Boolean stepping, @Nullable Collection<String> breakpoints) {
        return new MagratheaDebugSetup(Boolean.TRUE.equals(stepping), ordered(breakpoints));
    }

    /**
     * Whether a run was started debuggable — the question that decides if its
     * state changes are looked at for halting. Asked per run, not per
     * transition of an ordinary run, so those stay as cheap as they were.
     */
    public static boolean isDebuggable(StartRecord start) {
        return start.isDebug()
                || (start.getBreakpoints() != null && !start.getBreakpoints().isEmpty())
                || (start.getSubBreakpoints() != null
                        && !start.getSubBreakpoints().isEmpty());
    }

    /** Unmodifiable, duplicate-free, blank-free, in the order given. */
    public static Set<String> ordered(@Nullable Collection<String> names) {
        Set<String> out = new LinkedHashSet<>();
        if (names != null) {
            for (String name : names) {
                if (name != null && !name.isBlank()) out.add(name.trim());
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /** {@link #ordered} per workflow; workflows without a breakpoint are dropped. */
    public static Map<String, Set<String>> orderedByWorkflow(
            @Nullable Map<String, ? extends Collection<String>> byWorkflow) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (byWorkflow != null) {
            byWorkflow.forEach((workflow, names) -> {
                Set<String> set = ordered(names);
                if (workflow != null && !workflow.isBlank() && !set.isEmpty()) out.put(workflow.trim(), set);
            });
        }
        return Collections.unmodifiableMap(out);
    }
}
