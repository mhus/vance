package de.mhus.vance.shared.magrathea;

import de.mhus.vance.shared.magrathea.journal.StartRecord;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
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
 * @param stepping    halt before every state
 * @param breakpoints states to halt before, in the order given
 */
public record MagratheaDebugSetup(boolean stepping, Set<String> breakpoints) {

    /** No debugging: an ordinary run. */
    public static final MagratheaDebugSetup OFF = new MagratheaDebugSetup(false, Set.of());

    public MagratheaDebugSetup {
        breakpoints = ordered(breakpoints);
    }

    /** {@code true} when the run can be debugged at all. */
    public boolean active() {
        return stepping || !breakpoints.isEmpty();
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
                || (start.getBreakpoints() != null && !start.getBreakpoints().isEmpty());
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
}
