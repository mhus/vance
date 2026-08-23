/**
 * Mastodon as a Centauri feed source.
 *
 * <p>Its own addon rather than a third example inside the Feeds addon: this is
 * a real source somebody runs a feed on, not a demonstration, and it is the
 * first brain-side {@code FREEFORM} protocol — hashtags and firehose variants
 * are typed, not enumerated. Reaches into
 * {@code de.mhus.vance.brain.centauri.protocols} for the HTTP seam, which is
 * the allowed direction (an addon depends on the brain) and the reason
 * {@code CentauriHttpClient} lives there.
 *
 * <p>Design, measurements and the two findings that shaped the mapping:
 * {@code planning/centauri-mastodon.md} and
 * {@code planning/centauri-mastodon-messung.md}.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.addon.brain.mastodon;

import org.jspecify.annotations.NullMarked;
