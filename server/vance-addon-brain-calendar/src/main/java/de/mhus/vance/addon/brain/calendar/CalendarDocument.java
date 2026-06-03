package de.mhus.vance.addon.brain.calendar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory model of a {@code kind: calendar} document — a flat list
 * of {@link CalendarEvent}s. There is intentionally no nested
 * sub-calendar concept; if a user wants to separate work and private
 * events, they create two calendar documents and rely on {@code tags}
 * for in-calendar filtering.
 *
 * @param kind   always {@code "calendar"}.
 * @param events the event list. Order is not significant for rendering
 *               (the views sort chronologically) but is preserved
 *               round-trip so a hand-edited YAML stays in the order
 *               the user typed it.
 * @param extra  unknown top-level fields, passthrough for
 *               forward-compatibility.
 *
 * <p>Spec: {@code specification/doc-kind-calendar.md}.
 */
public record CalendarDocument(
        String kind,
        List<CalendarEvent> events,
        Map<String, Object> extra) {

    public CalendarDocument {
        if (kind == null || kind.isBlank()) kind = "calendar";
        if (events == null) events = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }

    public static CalendarDocument empty() {
        return new CalendarDocument("calendar", new ArrayList<>(), new LinkedHashMap<>());
    }
}
