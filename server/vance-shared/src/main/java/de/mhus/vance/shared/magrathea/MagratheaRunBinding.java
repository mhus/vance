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
 */
public record MagratheaRunBinding(
        @Nullable String sessionId,
        @Nullable String ownerProcessId,
        Set<RunCapability> capabilities) {

    private static final MagratheaRunBinding HEADLESS =
            new MagratheaRunBinding(null, null, Set.of());

    public MagratheaRunBinding {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    /** A run that belongs to a project and to nobody — scheduler, event, hook, tool. */
    public static MagratheaRunBinding headless() {
        return HEADLESS;
    }

    public boolean has(RunCapability capability) {
        return capabilities.contains(capability);
    }
}
