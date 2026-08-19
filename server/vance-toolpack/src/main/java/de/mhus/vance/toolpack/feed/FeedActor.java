package de.mhus.vance.toolpack.feed;

/**
 * The reader as a foreign source is allowed to see them: an opaque
 * pseudonym, salted per source instance.
 *
 * <p>Exists so a source can offer a reader-specific view (personalised
 * selection, server-side read marks, language preference) without ever
 * learning who the reader is. Derived centrally from
 * {@link FeedScope#userId()} plus the instance's salt — never by a
 * protocol implementation, so the salting cannot be got wrong once per
 * protocol.
 *
 * <p>The salt is <b>per instance</b> on purpose: a global salt would let
 * two sources join their profiles over the same reader. Per instance,
 * cross-source correlation is impossible while the reader stays
 * recognisable within one source — exactly as much as the feature needs.
 *
 * <p>Absence of a {@code FeedActor} is normal, not an error. See
 * {@link FeedFetch#actor()}.
 */
public record FeedActor(String pseudonym) {

    public FeedActor {
        if (pseudonym == null || pseudonym.isBlank()) {
            throw new IllegalArgumentException(
                    "actor pseudonym must not be blank — pass a null FeedActor for anonymous calls");
        }
    }
}
