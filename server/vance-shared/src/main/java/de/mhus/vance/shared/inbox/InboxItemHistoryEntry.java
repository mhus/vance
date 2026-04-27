package de.mhus.vance.shared.inbox;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Single audit-trail entry on an inbox item — captures
 * delegations, archive/dismiss/reopen events, etc. Embedded list,
 * append-only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboxItemHistoryEntry {

    /** {@code "CREATED"}, {@code "DELEGATED"}, {@code "ANSWERED"},
     *  {@code "DISMISSED"}, {@code "ARCHIVED"}, {@code "REOPENED"}. */
    private String action = "";

    /** Acting user-id (or system pseudonym for auto-events). */
    private String actor = "";

    /** Free-form details — for delegation: {@code from}, {@code to}, {@code note}. */
    private @Nullable String details;

    private Instant at = Instant.EPOCH;
}
