package de.mhus.vance.brain.runs;

import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One runtime's runs, behind one interface.
 *
 * <p>Magrathea runs and ThinkProcesses share almost nothing underneath —
 * append-only journal against mutable document, project-scoped against
 * session-owned, five statuses against seven plus eight close reasons.
 * What they do share is the thing a person looks for: a run with steps, a
 * state, a result, and something it might be waiting on. This interface
 * is that shared view, and each implementation is the only place that
 * knows how its runtime produces it.
 *
 * <p>Pure interface, no Spring: an addon that brings its own runs can
 * implement it. Beans are collected by {@link RunSourceRegistry}.
 *
 * <p><b>Authorisation: the caller has already been checked against the
 * project, and that is all that has been checked.</b> {@code RunController}
 * enforces {@code Resource.Project READ} before a read and
 * {@code Resource.Project WRITE} before an action, for every source alike;
 * the three built-in implementations add nothing beyond confining their
 * lookup to the {@code (tenantId, projectId)} they are handed, and a run
 * outside that scope is reported as absent rather than as forbidden.
 *
 * <p>An implementation whose runs sit behind a <em>narrower</em> resource
 * than the project — a session, a document, another project's data — must
 * enforce that itself, because nothing above it will. This is not a
 * hypothetical for addons only: the moment a source starts surfacing runs
 * the project grant does not cover, the project check stops being
 * sufficient and the gap is invisible from here.
 */
public interface RunSource {

    /**
     * Stable prefix of this source's composite run ids, e.g.
     * {@code workflow}. Must not contain {@code :}.
     */
    String sourceId();

    /** Runs of one project, newest first, at most {@code limit}. */
    List<RunSummaryDto> list(String tenantId, String projectId, int limit);

    /**
     * One run by its <em>native</em> id — the composite prefix is stripped
     * by the registry before dispatch.
     */
    Optional<RunDetailDto> get(String tenantId, String projectId, String nativeId);

    /**
     * What may be done to this run right now. Default: nothing, which is
     * the honest answer for every source in v1 — the read surface ships
     * before the control surface, and a source that cannot stop anything
     * should not offer a button that throws.
     */
    default Set<RunAction> allowedActions(String tenantId, String projectId, String nativeId) {
        return Set.of();
    }

    /**
     * Perform an action. Default: unsupported — see
     * {@link #allowedActions}. A source that overrides this must keep it
     * idempotent (stopping a stopped run is a no-op, not an error).
     */
    default void perform(String tenantId, String projectId, String nativeId,
                         RunAction action, String reason) {
        throw new UnsupportedOperationException(
                sourceId() + " runs cannot be controlled yet: " + action);
    }

    /**
     * May {@code subject} see this run at all?
     *
     * <p>Default true, which is the honest answer for a source whose runs
     * are project-scoped: the project check the caller already passed is
     * exactly the right check, and repeating it here would say nothing.
     *
     * <p>Override when some of your runs sit behind something narrower — a
     * session, a document, one person's conversation. The paragraph on
     * authorisation above says that is the source's job; this is where the
     * job gets done, and the registry applies it to both listing and
     * lookup so a source cannot secure one and forget the other.
     */
    default boolean visibleTo(
            de.mhus.vance.shared.permission.SecurityContext subject,
            String tenantId, String projectId, String nativeId) {
        return true;
    }
}
