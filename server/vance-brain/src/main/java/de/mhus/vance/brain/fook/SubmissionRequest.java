package de.mhus.vance.brain.fook;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Inbound report — both engine-driven {@code vance_support_request}
 * calls and UI-driven Fook-button clicks land here. A single
 * free-form text blob is all the reporter supplies; Fook derives
 * the title, type and severity during triage.
 *
 * <p>Submission IDs are assigned by {@link FookService#submit} on
 * enqueue — callers don't supply them.
 */
@Value
@Builder
public class SubmissionRequest {

    /** The whole report as the reporter wrote it. Markdown is
     *  fine; Fook reads it as-is. */
    String text;

    /** Identity of the reporter — used for the inbox-item target
     *  and as {@code reporter*} fields on the created ticket. */
    TicketReporter reporter;

    /** Origin context — populated by the {@code vance_support_request}
     *  tool from the calling process. {@code null} for
     *  {@link TicketReporter.Kind#USER_DIRECT} submissions when the
     *  UI couldn't reach a project/session (e.g. user-menu Fook
     *  button on the index page). */
    @Nullable TicketContext context;
}
