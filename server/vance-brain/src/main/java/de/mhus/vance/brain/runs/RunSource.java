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
 * <p><b>Each implementation enforces its own authorisation.</b> The
 * facade must not pull that together — Magrathea checks the project,
 * process insights check the process, and flattening the two would move a
 * permission boundary without anyone noticing.
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
}
