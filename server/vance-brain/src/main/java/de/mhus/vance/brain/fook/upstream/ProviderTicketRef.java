package de.mhus.vance.brain.fook.upstream;

import lombok.Builder;
import lombok.NonNull;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Identity of an external ticket. Returned by
 * {@link TicketProvider#create} and passed back into subsequent
 * calls like {@link TicketProvider#postComment} or
 * {@link TicketProvider#pollUpdates}.
 *
 * <p><b>Two identifiers, and the split is a rule rather than a
 * convenience.</b> {@link #getExternalId} is what the provider
 * addresses the ticket with and may be a secret — the collector
 * described in {@code planning/fook-vancetope-connector.md} issues a
 * capability handle there, and anyone holding it can read and comment
 * on the ticket. {@link #getDisplayId} is what may be shown: it goes
 * into log lines, inbox payloads and mount paths.
 *
 * <p>So: <b>never put {@code externalId} in front of a human or into a
 * log.</b> It is required rather than nullable-with-fallback precisely
 * because a fallback is the thing one forgets — a provider whose two
 * ids are the same (GitHub: issue number {@code 4287}) passes the same
 * value twice and nothing is lost.
 *
 * <p>{@link #getUrl} is the human-facing link. Nullable: a provider
 * may have no browsable page at all, and callers have to carry a
 * second wording for that case rather than print "null".
 */
@Value
@Builder
public class ProviderTicketRef {

    /** Provider name (matches {@link TicketProvider#name}). */
    @NonNull String provider;

    /**
     * Provider-native identifier used on the wire (e.g. {@code "4287"},
     * {@code "VANCE-42"}, or an opaque capability handle). Treat as a
     * secret — see the class comment.
     */
    @NonNull String externalId;

    /**
     * The identifier that may be shown to a person: log lines, inbox
     * payloads, mount paths. Where a provider has only one id, this is
     * that id.
     */
    @NonNull String displayId;

    /** Browsable URL, or {@code null} when the provider has no page. */
    @Nullable String url;
}
