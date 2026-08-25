package de.mhus.vance.addon.brain.calendar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a {@code kind: timeline} document: a declared
 * {@link TimelineAxis}, an ordered list of {@link TimelineLane}s, and
 * a flat list of {@link TimelineEntry} items that are periods or
 * points depending on whether they carry an end.
 *
 * <p>Sits next to {@code kind: calendar} in this addon and shares its
 * codec conventions ({@code $meta} header, YAML canonical, strings for
 * temporal values), but not its data model. A calendar is a list of
 * appointments on the Gregorian calendar; a timeline is a declared
 * number line with parallel lanes and nested periods. Deep time and
 * sub-minute reconstruction sit outside a calendar from opposite ends.
 *
 * @param kind    always {@code "timeline"}.
 * @param title   optional document-level heading rendered above the
 *                ruler. The Mongo document title is metadata and not
 *                visible to a reader looking at an embedded timeline,
 *                so the body may carry its own.
 * @param axis    the axis declaration; never {@code null} (defaults
 *                to a forward numeric axis).
 * @param lanes   declared lanes in render order. May be empty — all
 *                entries then share one unnamed lane.
 * @param entries flat entry list. Input order is preserved round-trip
 *                so a hand-edited file keeps its shape, but carries no
 *                meaning: the renderer sorts by position.
 * @param extra   unknown top-level fields, passthrough for
 *                forward-compatibility.
 *
 * <p>Spec: {@code specification/public/doc-kind-timeline.md}.
 */
public record TimelineDocument(
        String kind,
        @org.jspecify.annotations.Nullable String title,
        TimelineAxis axis,
        List<TimelineLane> lanes,
        List<TimelineEntry> entries,
        Map<String, Object> extra) {

    public TimelineDocument {
        if (kind == null || kind.isBlank()) kind = "timeline";
        if (axis == null) axis = TimelineAxis.defaults();
        if (lanes == null) lanes = new ArrayList<>();
        if (entries == null) entries = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static TimelineDocument empty() {
        return new TimelineDocument(
                "timeline", null, TimelineAxis.defaults(),
                new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>());
    }
}
