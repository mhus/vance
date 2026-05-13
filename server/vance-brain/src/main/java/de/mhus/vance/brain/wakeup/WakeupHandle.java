package de.mhus.vance.brain.wakeup;

import java.time.Instant;

/**
 * Summary of an active wakeup, returned by
 * {@link WakeupRegistry#list(String)}. Plain record so the wire-form
 * is just a JSON object for the {@code wakeup_list} tool and for
 * future debugging endpoints.
 *
 * @param correlationId opaque id assigned at schedule time
 * @param label         caller-supplied human-readable hint
 * @param fireAt        absolute wall-clock instant the wakeup will fire
 */
public record WakeupHandle(String correlationId, String label, Instant fireAt) {
}
