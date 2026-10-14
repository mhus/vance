package de.mhus.vance.brain.wonko;

import org.jspecify.annotations.Nullable;

/**
 * A category change Wonko requests for its own mandate (plan §6.4). The
 * request is a wish; {@link WonkoMandateRules} decides what is applied, what
 * becomes a proposal for the user and what is refused.
 *
 * @param category the category the operation works on
 * @param target   rename/merge/split target category, or the category a
 *                 topic is classified into
 * @param topic    topic key for {@link Op#CLASSIFY}
 */
record WonkoCategoryChange(
        Op op,
        String category,
        @Nullable String target,
        @Nullable MandateLevel level,
        @Nullable String description,
        @Nullable String topic,
        @Nullable String reason) {

    enum Op {
        /** New category at the default level. */
        DEFINE,
        /** Change the description. */
        DESCRIBE,
        /** Rename {@code category} to {@code target}. */
        RENAME,
        /** New category {@code target} split off {@code category}, inheriting its level. */
        SPLIT,
        /** Fold {@code category} into {@code target}; the stricter level wins. */
        MERGE,
        /** Set the level of {@code category}. */
        LEVEL,
        /** Move {@code topic} into category {@code target}. */
        CLASSIFY
    }
}
