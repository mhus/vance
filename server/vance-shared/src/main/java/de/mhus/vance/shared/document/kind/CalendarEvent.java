package de.mhus.vance.shared.document.kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One event in a {@code kind: calendar} document.
 *
 * <p>{@link #start} and {@link #end} are kept as raw strings so the
 * codec stays agnostic to time-zone semantics — the renderer parses
 * them as {@code LocalDate} (when {@link #allDay} is true) or
 * {@code OffsetDateTime} / {@code LocalDateTime} otherwise. Storing
 * strings round-trips losslessly: a value with offset {@code +02:00}
 * survives, a value without offset is shown in the viewer's local
 * zone, an {@code allDay} date never gets a phantom time component.
 *
 * <p>{@link #recurrence} is an RFC 5545 RRULE expression
 * ({@code "FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261231T000000Z"}). The
 * codec passes it through verbatim; expansion happens in the renderer.
 * Empty / {@code null} means non-recurring.
 *
 * <p>{@link #id} is a stable identifier (typically UUID) used for
 * recurrence-exception identity and ICS round-trips. The codec
 * auto-fills missing ids on read so callers can rely on it being
 * present after parse.
 *
 * <p>Spec: {@code specification/doc-kind-calendar.md} §2.
 */
public record CalendarEvent(
        String id,
        String title,
        String start,
        @Nullable String end,
        boolean allDay,
        @Nullable String location,
        List<String> attendees,
        @Nullable String recurrence,
        @Nullable String color,
        List<String> tags,
        @Nullable String notes,
        Map<String, Object> extra) {

    public CalendarEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(start, "start");
        if (attendees == null) attendees = new ArrayList<>();
        if (tags == null) tags = new ArrayList<>();
        if (extra == null) extra = new LinkedHashMap<>();
    }
}
