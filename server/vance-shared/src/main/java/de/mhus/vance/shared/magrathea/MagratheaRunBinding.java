package de.mhus.vance.shared.magrathea;

import de.mhus.vance.api.magrathea.RunCapability;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a run is attached to at start time — the difference between a plan
 * that belongs to a project and one that belongs to somebody.
 *
 * <p>Passed into {@code MagratheaWorkflowService.start} instead of being
 * discovered later, because the derived {@link RunCapability} set has to be
 * settled before the first state runs: it decides whether the plan can be
 * started at all.
 *
 * @param sessionId      session the run belongs to, or null for headless
 * @param ownerProcessId ThinkProcess that waits for the result and
 *                       represents the run outwards, or null
 * @param capabilities   what the two above amount to; resolved by the
 *                       caller because deciding whether a session has a
 *                       human owner needs the session service
 * @param derivedParamKeys parameters that were <em>read out of what somebody
 *                       said</em> rather than passed in. Carried here because
 *                       it answers the same question as the rest — under which
 *                       circumstances did this run begin — and it has to reach
 *                       the same {@code StartRecord}. Without it, a run that
 *                       did something surprising cannot be explained: the
 *                       parameters are on record, but not that one of them was
 *                       an interpretation.
 */
public record MagratheaRunBinding(
        @Nullable String sessionId,
        @Nullable String ownerProcessId,
        Set<RunCapability> capabilities,
        Set<String> derivedParamKeys) {

    private static final MagratheaRunBinding HEADLESS =
            new MagratheaRunBinding(null, null, Set.of(), Set.of());

    public MagratheaRunBinding {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        derivedParamKeys = derivedParamKeys == null ? Set.of() : Set.copyOf(derivedParamKeys);
    }

    /** Bound run, nothing interpreted — the common case. */
    public MagratheaRunBinding(
            @Nullable String sessionId,
            @Nullable String ownerProcessId,
            Set<RunCapability> capabilities) {
        this(sessionId, ownerProcessId, capabilities, Set.of());
    }

    /** A run that belongs to a project and to nobody — scheduler, event, hook, tool. */
    public static MagratheaRunBinding headless() {
        return HEADLESS;
    }

    /** Same binding, recording which parameters were interpreted rather than given. */
    public MagratheaRunBinding withDerivedParams(Set<String> keys) {
        return new MagratheaRunBinding(sessionId, ownerProcessId, capabilities, keys);
    }

    public boolean has(RunCapability capability) {
        return capabilities.contains(capability);
    }
}
