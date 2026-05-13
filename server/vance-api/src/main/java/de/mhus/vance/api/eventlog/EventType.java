package de.mhus.vance.api.eventlog;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Lifecycle stage an event represents. A single trigger run typically
 * emits {@code TRIGGERED → STARTED → (COMPLETED|FAILED)} or
 * {@code TRIGGERED → SKIPPED}, all sharing the same {@code correlationId}.
 */
@GenerateTypeScript("eventlog")
public enum EventType {
    /** Auslöser hat gefeuert (Cron-Tick, Webhook-Hit, External-Push). */
    TRIGGERED,
    /** Process wurde erzeugt und läuft. */
    STARTED,
    /** Process erfolgreich beendet (Status {@code DONE}). */
    COMPLETED,
    /** Process mit Fehler beendet. */
    FAILED,
    /** Trigger gefeuert, aber kein Process gestartet — z.B. Overlap-Skip. */
    SKIPPED,
    /** Laufender Process wurde durch Overlap-{@code CANCEL_PREVIOUS} terminiert. */
    CANCELLED
}
