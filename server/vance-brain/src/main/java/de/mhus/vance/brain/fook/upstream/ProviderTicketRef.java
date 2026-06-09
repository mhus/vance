package de.mhus.vance.brain.fook.upstream;

import lombok.Builder;
import lombok.Value;

/**
 * Identity of an external ticket. Returned by
 * {@link TicketProvider#create} and passed back into subsequent
 * calls like {@link TicketProvider#postComment} or
 * {@link TicketProvider#pollUpdates}.
 *
 * <p>{@link #getExternalId} is provider-specific (GitHub issue
 * number, GitLab issue iid, Jira issue key); {@link #getUrl} is
 * the human-facing link shown in inbox items.
 */
@Value
@Builder
public class ProviderTicketRef {

    /** Provider name (matches {@link TicketProvider#name}). */
    String provider;

    /** Provider-native identifier (e.g. {@code "4287"}, {@code "VANCE-42"}). */
    String externalId;

    /** Browsable URL. */
    String url;
}
