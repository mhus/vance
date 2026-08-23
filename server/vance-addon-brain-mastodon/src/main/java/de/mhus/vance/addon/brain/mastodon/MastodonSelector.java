package de.mhus.vance.addon.brain.mastodon;

import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One Mastodon stream, parsed from the free-text selector a reader typed.
 *
 * <p>Grammar: {@code hashtag:<tag>} or {@code public:all|local|remote}. Two
 * forms, both prefixed — an unprefixed selector is refused rather than guessed,
 * because {@code news} is a plausible hashtag <em>and</em> a plausible mistake
 * for {@code public:local}, and a source that guesses wrong here shows a
 * stranger's timeline without saying so.
 *
 * <p>{@code account:} is deliberately absent. It needs a second call to resolve
 * {@code @name@host} to the numeric id the statuses endpoint wants, plus a
 * cache, plus its own failure mode — see {@code planning/centauri-mastodon.md}
 * §4. Being refused by name ("not supported yet") is better than being accepted
 * and returning nothing.
 */
record MastodonSelector(Kind kind, String value) {

    enum Kind { HASHTAG, PUBLIC }

    static final String PREFIX_HASHTAG = "hashtag:";
    static final String PREFIX_PUBLIC = "public:";

    /** The whole-instance firehose variants of {@code public:}. */
    static final String PUBLIC_ALL = "all";
    static final String PUBLIC_LOCAL = "local";
    static final String PUBLIC_REMOTE = "remote";

    private static final String USAGE =
            "use hashtag:<tag> (e.g. hashtag:opensource) or "
                    + "public:all | public:local | public:remote";

    /**
     * Why {@code raw} is unusable, or empty when it is fine.
     *
     * <p>This is the whole point of {@code FeedSourceInstance.validateSelector}
     * and the reason this protocol is the first brain-side {@code FREEFORM}
     * source: without it, a trailing space or a leading {@code #} produces an
     * empty stream and no explanation.
     */
    static Optional<String> complain(@Nullable String raw) {
        String selector = raw == null ? "" : raw.trim();
        if (selector.isEmpty()) {
            return Optional.of("selector must not be empty — " + USAGE);
        }
        String lower = selector.toLowerCase(Locale.ROOT);
        if (lower.startsWith(PREFIX_HASHTAG)) {
            return complainAboutTag(selector.substring(PREFIX_HASHTAG.length()));
        }
        if (lower.startsWith(PREFIX_PUBLIC)) {
            return complainAboutPublic(selector.substring(PREFIX_PUBLIC.length()));
        }
        if (selector.startsWith("#")) {
            // The single most likely typo, and it deserves the specific answer
            // rather than the generic one.
            return Optional.of("write 'hashtag:" + selector.substring(1).trim()
                    + "' instead of '" + selector + "'");
        }
        int colon = selector.indexOf(':');
        String unknown = colon > 0 ? selector.substring(0, colon) : selector;
        return Optional.of("unknown selector kind '" + unknown + "' — " + USAGE);
    }

    /**
     * Parse a selector that {@link #complain} accepts.
     *
     * @throws IllegalArgumentException when it does not — callers validate
     *         first; this is the guard, not the report.
     */
    static MastodonSelector parse(@Nullable String raw) {
        Optional<String> complaint = complain(raw);
        if (complaint.isPresent()) {
            throw new IllegalArgumentException(complaint.get());
        }
        String selector = raw == null ? "" : raw.trim();
        String lower = selector.toLowerCase(Locale.ROOT);
        if (lower.startsWith(PREFIX_HASHTAG)) {
            return new MastodonSelector(
                    Kind.HASHTAG, selector.substring(PREFIX_HASHTAG.length()).trim());
        }
        return new MastodonSelector(
                Kind.PUBLIC, lower.substring(PREFIX_PUBLIC.length()).trim());
    }

    // ── internals ────────────────────────────────────────────────────

    private static Optional<String> complainAboutTag(String rawTag) {
        String tag = rawTag.trim();
        if (tag.isEmpty()) {
            return Optional.of("hashtag selector needs a tag, e.g. hashtag:opensource");
        }
        if (tag.startsWith("#")) {
            return Optional.of("omit the '#': write hashtag:" + tag.substring(1).trim());
        }
        boolean hasLetter = false;
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            // Unicode-aware on purpose: #Grüße and #日本 are valid Mastodon tags,
            // so an [A-Za-z0-9_] check would refuse tags the instance accepts.
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (!Character.isDigit(c) && c != '_') {
                return Optional.of("hashtag '" + tag + "' contains '" + c
                        + "' — tags are letters, digits and underscore only");
            }
        }
        if (!hasLetter) {
            return Optional.of("hashtag '" + tag + "' has no letter — "
                    + "Mastodon does not index digit-only tags");
        }
        return Optional.empty();
    }

    private static Optional<String> complainAboutPublic(String rawScope) {
        String scope = rawScope.trim().toLowerCase(Locale.ROOT);
        if (scope.equals(PUBLIC_ALL) || scope.equals(PUBLIC_LOCAL)
                || scope.equals(PUBLIC_REMOTE)) {
            return Optional.empty();
        }
        if (scope.isEmpty()) {
            return Optional.of("public selector needs a scope: "
                    + "public:all, public:local or public:remote");
        }
        return Optional.of("unknown public scope '" + scope
                + "' — use all, local or remote");
    }
}
