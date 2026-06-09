package de.mhus.vance.brain.fook.upstream;

import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * One delta returned by {@link TicketProvider#pollUpdates}. Reports
 * whatever changed since the last sync on a tracked ticket — state
 * change, new comments, both, or neither (in which case no update
 * is emitted at all).
 */
@Value
@Builder
public class ProviderTicketUpdate {

    /** Which ticket this update belongs to. */
    ProviderTicketRef ref;

    /**
     * Current upstream state. For GitHub: {@code open} or
     * {@code closed}. Other providers map onto a coarse string —
     * Fook only stores it for display; semantics stay provider-side.
     */
    @Nullable String state;

    /** Snapshot of the upstream timestamp for the most recent
     *  change. Used to suppress redundant inbox-items when polling
     *  re-sees an unchanged delta. */
    @Nullable Instant updatedAt;

    /** New comments since the last sync, in chronological order. */
    List<ProviderComment> newComments;

    @Value
    @Builder
    public static class ProviderComment {
        /** Provider-native comment id, for de-duplication. */
        String externalId;
        /** Display label for the author (e.g. {@code "ford-prefect"}
         *  on GitHub). Anonymous-style providers may pass a hash. */
        String author;
        /** Markdown body. */
        String body;
        Instant createdAt;
    }
}
