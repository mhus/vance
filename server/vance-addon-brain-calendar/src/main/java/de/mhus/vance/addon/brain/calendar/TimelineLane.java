package de.mhus.vance.addon.brain.calendar;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One lane (track) of a {@code kind: timeline} document — the vertical
 * axis a calendar's month grid does not have.
 *
 * <p>Lanes are what let parallel strands be read against one clock:
 * suspect / victim / witness / police in a reconstruction,
 * stratigraphy / climate / fauna in a geological chart. Purely
 * presentational ordering — a lane is not a scope, carries no
 * permissions, and nothing cascades along it.
 *
 * <p>Declaring lanes is optional. An entry may name a lane that no
 * declaration mentions; it then appears after the declared ones in
 * first-appearance order. Declaration buys two things: <b>order</b>
 * and a lane that exists while still empty — an empty lane is a
 * statement ("we have no record of the witness that night") and
 * would be inexpressible if lanes were derived from the entries.
 *
 * @param id    stable lane id, referenced by {@link TimelineEntry#lane()}.
 * @param title display label; falls back to {@link #id} when absent.
 * @param color palette name or CSS colour applied to entries in this
 *              lane that declare none of their own.
 */
public record TimelineLane(
        String id,
        @Nullable String title,
        @Nullable String color) {

    public TimelineLane {
        Objects.requireNonNull(id, "id");
    }

    /** Display label — {@link #title} when set, otherwise the raw {@link #id}. */
    public String displayTitle() {
        return (title == null || title.isBlank()) ? id : title;
    }
}
