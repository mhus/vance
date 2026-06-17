package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import java.time.Instant;
import java.util.Map;

/**
 * Spring application-event that fans out to {@link UrsaHookDispatcher}.
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
public record UrsaHookFireableEvent(
        String tenantId,
        String projectId,
        UrsaHookEventName event,
        Instant firedAt,
        Map<String, Object> payload) {

    public UrsaHookFireableEvent {
        if (firedAt == null) {
            firedAt = Instant.now();
        }
        if (payload == null) {
            payload = Map.of();
        }
    }

    public static UrsaHookFireableEvent of(
            String tenantId,
            String projectId,
            UrsaHookEventName event,
            Map<String, Object> payload) {
        return new UrsaHookFireableEvent(tenantId, projectId, event, Instant.now(), payload);
    }
}
