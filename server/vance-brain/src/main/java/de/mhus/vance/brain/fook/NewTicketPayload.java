package de.mhus.vance.brain.fook;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input to {@link FookTicketService#createTicket}. The ticket's UUID
 * and timestamps are filled in by the service — callers don't supply
 * them. Status is implicitly {@code new} (Frankie owns the rest of
 * the status lifecycle).
 */
@Value
@Builder
public class NewTicketPayload {

    /** Short imperative summary — first line of the ticket. */
    String title;

    /** Markdown body. The triage LLM may normalise the reporter's
     *  raw text; whatever it returns lands here verbatim. */
    String description;

    /** {@code bug}, {@code feature}, {@code question}, or {@code other}. */
    String type;

    /** {@code low}, {@code medium}, or {@code high}. */
    String severity;

    /** Identity of the reporter — populated server-side from the
     *  submission, not the LLM. */
    TicketReporter reporter;

    /** Origin context — populated server-side from the submission. */
    @Nullable TicketContext context;

    /** Optional triage note from the LLM explaining why it ruled
     *  this a new ticket rather than a merge. */
    @Nullable String triageNote;

    /** UUIDs of existing tickets to link as {@code relatedTo} on the
     *  newly-created ticket. {@code rootCauseOf}/{@code duplicateOf}
     *  aren't reachable from a "new ticket" decision — only
     *  {@code merge_into} touches those, via {@link RelationsPatch}. */
    List<String> relatedTickets;

    /**
     * Transport-approval flag persisted to {@code $meta.transportApproval}.
     * Set by {@link FookService} from the current
     * {@code fook.upstream.mode} setting:
     * <ul>
     *   <li>{@code mode=automatic} → {@code "auto"}</li>
     *   <li>{@code mode=manual}    → {@code "pending"}</li>
     *   <li>{@code mode=never}     → {@code "none"}</li>
     * </ul>
     * The sender-tick picks tickets up when this is {@code auto} or
     * {@code approved}.
     */
    @Nullable String transportApproval;

    /**
     * The id of the inbox-item written at triage-time. Recorded so
     * the sender-tick can update the same item with the upstream
     * URL instead of creating a second one for the transfer event.
     */
    @Nullable String inboxItemId;
}
