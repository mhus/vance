package de.mhus.vance.brain.fook;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Inter-ticket links surfaced both to the Fook triage LLM (as part
 * of a {@link TicketCandidate}) and to Frankie later. The single
 * scalar {@link #getDuplicateOf} lives in {@code $meta} so the
 * candidate-search query can filter on it cheaply; the list-valued
 * relations live in the YAML body under {@code relations:}.
 */
@Value
@Builder
public class TicketRelations {

    /** UUID of another ticket this one duplicates. Skalar — sits
     *  in {@code $meta.duplicateOf} on persisted documents. */
    @Nullable String duplicateOf;

    /** UUIDs of tickets that are symptoms of THIS ticket. */
    List<String> rootCauseOf;

    /** UUIDs of generally-related tickets (not cause, not duplicate). */
    List<String> relatedTo;
}
