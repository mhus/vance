package de.mhus.vance.brain.fook.upstream;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Result of {@link TicketProvider#checkConnection} — drives the
 * "Test Connection" button in the Fook-Upstream setting form.
 */
@Value
@Builder
public class HealthCheckResult {

    /** Whether the provider could authenticate + reach the target. */
    boolean ok;

    /**
     * One-line human-readable summary. On {@code ok=true}: e.g.
     * "Authorized as vance-fook-bot, target mhus/vance accessible
     *  (124 open issues)". On {@code ok=false}: brief failure
     * description ("HTTP 401: Bad credentials").
     */
    String message;

    /**
     * Optional details — e.g. authenticated account login, target
     * repo description, last-modified-by stats. Surfaced as a
     * collapsible details block in the form UI.
     */
    @Nullable String details;
}
