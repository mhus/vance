/**
 * Persistence layer for think-processes.
 *
 * <p>A think-process is a running instance of a think-engine — the engine
 * itself (algorithm) lives in {@code de.mhus.vance.brain.thinkengine}; here
 * we only deal with the persistent state that every process has in common:
 * identity, session ownership, engine reference, status, and the pending
 * message queue that feeds the engine's lane.
 *
 * <p>Access goes through {@link ThinkProcessService}; the repository is
 * package-private, per CLAUDE.md data-ownership rule.
 */
@NullMarked
package de.mhus.vance.shared.thinkprocess;

import org.jspecify.annotations.NullMarked;
