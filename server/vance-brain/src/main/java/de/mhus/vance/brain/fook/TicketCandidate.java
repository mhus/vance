package de.mhus.vance.brain.fook;

import lombok.Builder;
import lombok.Value;

/**
 * Lightweight projection of a {@code fook-ticket} document, returned
 * from {@link FookTicketService#searchSimilar} and rendered into the
 * {@code fook} recipe's Pebble prompt as a candidate card. Carries
 * exactly the fields the recipe template references — no internals,
 * no timestamps, no reporter identity (the triage LLM doesn't need
 * to know who filed a similar ticket).
 *
 * <p>Lombok getters expose the fields as bean-properties so Pebble's
 * dot-access (e.g. {@code {{ c.relations.duplicateOf }}}) resolves
 * naturally without an explicit map projection.
 */
@Value
@Builder
public class TicketCandidate {

    String id;
    String type;
    String severity;
    String status;
    String title;
    String description;
    TicketRelations relations;
}
