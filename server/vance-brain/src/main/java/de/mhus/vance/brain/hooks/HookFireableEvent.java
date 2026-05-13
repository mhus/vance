package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import java.time.Instant;
import java.util.Map;

/**
 * Spring application-event that fans out to {@link HookDispatcher}.
 * Trigger emitters (process lifecycle, inbox, session, …) publish this
 * with a typed payload map. The dispatcher does not interpret the
 * payload — it just hands it to the runners as the {@code event} host
 * binding.
 *
 * <p>Carry only data, no references to mutable Mongo documents — the
 * dispatcher runs asynchronously and the publisher's transaction may
 * have committed (or rolled back) by the time the hook script reads
 * it. Producers materialise the relevant fields into the map before
 * publishing.
 */
public record HookFireableEvent(
        String tenantId,
        String projectId,
        HookEventName event,
        Instant firedAt,
        Map<String, Object> payload) {

    public HookFireableEvent {
        if (firedAt == null) {
            firedAt = Instant.now();
        }
        if (payload == null) {
            payload = Map.of();
        }
    }

    public static HookFireableEvent of(
            String tenantId,
            String projectId,
            HookEventName event,
            Map<String, Object> payload) {
        return new HookFireableEvent(tenantId, projectId, event, Instant.now(), payload);
    }
}
