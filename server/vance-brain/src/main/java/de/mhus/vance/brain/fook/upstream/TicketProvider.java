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
