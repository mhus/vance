/**
 * Centauri feed contract — the consumption side of foreign time-ordered
 * streams (news aggregators, public Mastodon timelines).
 *
 * <p>This package holds the wire-neutral contract only: the
 * {@link de.mhus.vance.toolpack.feed.FeedProtocol} SPI, the configured
 * {@link de.mhus.vance.toolpack.feed.FeedSourceInstance} and the records
 * that travel between them. The dispatcher, the instance factory and the
 * gate live in {@code vance-brain} package {@code de.mhus.vance.brain.centauri}
 * — mirroring how {@code de.mhus.vance.toolpack.research} relates to
 * {@code de.mhus.vance.brain.zarniwoop}.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.toolpack.feed;

import org.jspecify.annotations.NullMarked;
