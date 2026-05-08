package de.mhus.vance.api.eddie;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Lifecycle of an Eddie-mediated WS handover. See
 * {@code specification/eddie-engine.md} §8.5.
 *
 * <ul>
 *   <li>{@link #ACTIVE} — User-WS rebinds to the worker session;
 *       Eddie's LLM lane pauses, engine-eventhandlers stay alive
 *       (worker-terminal triggers, return).</li>
 *   <li>{@link #RETURNING} — return choreography in progress
 *       (cleanup + rebind back to Eddie). Transient state, used by
 *       the auto-rebind path on worker terminal.</li>
 * </ul>
 */
@GenerateTypeScript("eddie")
public enum MediationState {
    ACTIVE,
    RETURNING
}
