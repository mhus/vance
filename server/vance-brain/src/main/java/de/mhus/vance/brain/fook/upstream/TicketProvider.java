package de.mhus.vance.brain.fook.upstream;

import java.time.Instant;
import java.util.List;

/**
 * Adapter to one external ticket-tracking system. v1 has one
 * implementation, {@code GitHubTicketProvider}. Other providers
 * (GitLab, Gitea, Jira, custom REST) can be added as new
 * {@code @Component} beans without touching {@code FookUpstreamService}.
 *
 * <p>Implementations are stateless. The
 * {@code FookUpstreamService} selects the right bean at runtime
 * via the {@code fook.upstream.providerType} setting and the
 * {@link #name} of each candidate.
 *
 * <p>Failures should throw {@link ProviderException} with a
 * {@code retryable} flag — transient network/rate-limit
 * conditions should retry on the next tick; permanent ones
 * (bad token, repo gone) should fail the ticket fast.
 */
public interface TicketProvider {

    /** Identifier matched against {@code fook.upstream.providerType}. */
    String name();

    /**
     * Whether {@link #pollUpdates} is meaningful for this provider.
     *
     * <p>Declared rather than inferred, because an empty result from
     * {@code pollUpdates} cannot distinguish "nothing changed" from
     * "I have no way to ask". {@code FookUpstreamService.pollTick}
     * exits on this, not on an empty list.
     *
     * <p>Not to be confused with the {@code fook.upstream.statusPoll.enabled}
     * setting: that is the operator saying "I do not want this", this is
     * the adapter saying "I cannot do this". Two questions, two answers.
     */
    default boolean supportsPolling() {
        return true;
    }

    /**
     * How many tracked tickets one poll pass may ask about.
     *
     * <p>Declared by the adapter because it is a property of the wire: a
     * provider with a {@code ?since=} listing answers for all of them in one
     * request and has no reason to cap, while one that has to ask per ticket
     * pays a round trip each.
     *
     * <p><b>Which</b> tickets go into the batch is not the adapter's
     * business — {@code FookUpstreamService.pollTick} orders them by how
     * long ago each was last asked about and stamps every one it handed
     * over, so a cap rotates instead of pinning the same head of the list.
     * An adapter that slices the list itself would break that, because it
     * cannot see the timestamps.
     *
     * @return the maximum batch size; {@link Integer#MAX_VALUE} for "no cap"
     */
    default int pollBatchSize() {
        return Integer.MAX_VALUE;
    }

    /** Create an external ticket from the anonymized draft. */
    ProviderTicketRef create(ProviderTicketDraft draft) throws ProviderException;

    /** Add a comment to an existing external ticket — used for
     *  reporter-replies coming back through the inbox. */
    void postComment(ProviderTicketRef ref, String body) throws ProviderException;

    /**
     * Poll for state/comment deltas on the supplied tracked tickets
     * since {@code since}. Implementations decide whether to issue
     * one round-trip per ticket, a batched query, or a since-anchored
     * server-side filter — that's a provider-specific call.
     *
     * <p>Returns only tickets whose state OR comment-list actually
     * changed since {@code since}. Empty list = no updates.
     */
    List<ProviderTicketUpdate> pollUpdates(
            List<ProviderTicketRef> tracked, Instant since) throws ProviderException;

    /**
     * Smoke-test for the "Test Connection" button in the Fook-Upstream
     * setting form. Should be cheap (one GET to the provider's whoami
     * endpoint plus a repo-existence probe), and must not throw —
     * failure cases populate {@link HealthCheckResult#isOk}={@code false}.
     */
    HealthCheckResult checkConnection();
}
