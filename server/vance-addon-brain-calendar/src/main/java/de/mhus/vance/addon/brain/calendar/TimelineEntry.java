package de.mhus.vance.addon.brain.calendar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One entry in a {@code kind: timeline} document — a <b>period</b> when
 * it carries {@link #to}, a <b>point</b> when it does not.
 *
 * <p>There is deliberately no second entry type. "Period" and "event"
 * differ by exactly one field, the way a calendar event with an
 * {@code end} differs from one without; two arrays would mean two
 * schemas for the same thing and two render paths that drift apart.
 *
 * <p><b>Positions are strings</b> ({@link #from} / {@link #to}), read
 * against the document's {@link TimelineAxis}: a bare number on a
 * numeric axis, an ISO-8601 instant on a date-time axis. Keeping them
 * textual means the file round-trips exactly as typed — {@code 201.40}
 * stays {@code 201.40} — and the codec never has to guess a numeric
 * type it would then have to re-render.
 *
 * <p><b>Uncertainty is first-class</b>, not a note. "Last seen between
 * 21:40 and 22:05" and "201.4 ± 0.2 Ma" are the substance of a crime
 * reconstruction and of a geological chart respectively; a model that
 * cannot express them forces the author into {@link #notes}, and the
 * drawing then shows a hard edge where there is none — it renders a
 * claim nobody made. {@link #fromEarliest} / {@link #fromLatest} bound
 * the start, {@link #toEarliest} / {@link #toLatest} the end. All four
 * are optional and independent: a period can have a known start and a
 * fuzzy end.
 *
 * <p>Spec: {@code specification/public/doc-kind-timeline.md} §2.
 *
 * @param id           stable identifier. Auto-filled with a UUID on
 *                     read when missing, so {@link #parent} references
 *                     and the renderer's selection state have
 *                     something to hold on to.
 * @param title        display label. Required.
 * @param from         start position on the axis. Required.
 * @param to           end position; {@code null} makes this a point.
 * @param fromEarliest earliest the start could be.
 * @param fromLatest   latest the start could be.
 * @param toEarliest   earliest the end could be.
 * @param toLatest     latest the end could be.
 * @param lane         lane id this entry belongs to; {@code null} puts
 *                     it in the unnamed default lane.
 * @param parent       id of the entry this one sits inside (era ⊃
 *                     period ⊃ epoch). Flat list plus a parent
 *                     reference rather than nested YAML: one update
 *                     path, and nesting depth stays a matter of
 *                     content instead of document structure.
 * @param color        palette name or CSS colour.
 * @param tags         free-form filter tags.
 * @param notes        multi-line description.
 * @param extra        unknown entry keys, passthrough.
 */
public record TimelineEntry(
        String id,
        String title,
        String from,
        @Nullable String to,
        @Nullable String fromEarliest,
        @Nullable String fromLatest,
        @Nullable String toEarliest,
        @Nullable String toLatest,
        @Nullable String lane,
        @Nullable String parent,
        @Nullable String color,
        List<String> tags,
        @Nullable String notes,
        Map<String, Object> extra) {

    public TimelineEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(from, "from");
        if (tags == null) tags = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    /** True when this entry spans a range rather than marking a moment. */
    public boolean isPeriod() {
        return to != null;
    }

    /** True when any of the four uncertainty bounds is set. */
    public boolean hasUncertainty() {
        return fromEarliest != null || fromLatest != null
                || toEarliest != null || toLatest != null;
    }
}
