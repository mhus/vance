package de.mhus.vance.brain.fook;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Input to {@link FookTicketService#createTicket}. The ticket's UUID
 * and timestamps are filled in by the service — callers don't supply
 * them. Status is implicitly {@code new} (Lunkwill owns the rest of
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
}
