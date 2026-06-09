package de.mhus.vance.brain.fook;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Full read-projection of a persisted {@code fook-ticket} YAML
 * document. Used by callers that need more than a candidate card —
 * Lunkwill, admin UIs, debugging. {@link FookTicketService#readTicket}
 * returns this.
 */
@Value
@Builder
public class TicketDocument {

    // ── $meta scalars ───────────────────────────────────────────────
    String id;
    String title;
    String type;
    String severity;
    String status;
    Instant createdAt;
    @Nullable Instant triagedAt;
    @Nullable String triagedBy;

    // ── body ────────────────────────────────────────────────────────
    String description;
    @Nullable String triageNote;
    @Nullable TicketContext context;
    TicketRelations relations;
    TicketReporter reporter;
}
