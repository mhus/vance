package de.mhus.vance.shared.document.kind;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Parser and serialiser for {@code kind: calendar} document bodies —
 * mirrors {@code calendarCodec.ts}. JSON and YAML only; markdown is
 * intentionally not supported (calendar events are nested enough that
 * a markdown table loses fidelity — see §3 of the calendar spec).
 *
 * <p>Date/time values ({@code start}, {@code end}) and recurrence
 * rules are pass-through strings. The codec does not parse ISO-8601
 * or RFC 5545 — that's the renderer's job. The only structural
 * requirements are {@code title} (non-blank) and {@code start}
 * (non-blank); events failing either are silently dropped.
 *
 * <p>{@link CalendarEvent#id()} is auto-filled with a UUID when
 * missing on read, so the editor's stable-id assumptions hold even
 * for documents the LLM wrote without an explicit id.
 */
public final class CalendarCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> JSON_MAP =
            new TypeReference<>() {};

    private CalendarCodec() {
        // utility class
    }

    public static CalendarDocument parse(String body, @Nullable String mimeType) {
        if (isJson(mimeType)) return parseJson(body);
        if (isYaml(mimeType)) return parseYaml(body);
        throw new KindCodecException("Unsupported mime type for calendar: " + mimeType);
    }

    public static String serialize(CalendarDocument doc, @Nullable String mimeType) {
        if (isJson(mimeType)) return serializeJson(doc);
        if (isYaml(mimeType)) return serializeYaml(doc);
        throw new KindCodecException("Unsupported mime type for calendar: " + mimeType);
    }

    public static boolean supports(@Nullable String mimeType) {
        return isJson(mimeType) || isYaml(mimeType);
    }

    // ── Mime ───────────────────────────────────────────────────────

    private static boolean isJson(@Nullable String mime) {
        return "application/json".equals(mime);
    }

    private static boolean isYaml(@Nullable String mime) {
        return "application/yaml".equals(mime)
                || "application/x-yaml".equals(mime)
                || "text/yaml".equals(mime)
                || "text/x-yaml".equals(mime);
    }

    // ── JSON / YAML ────────────────────────────────────────────────

    private static CalendarDocument parseJson(String body) {
        if (body.isBlank()) return CalendarDocument.empty();
        Map<String, Object> parsed;
        try {
            parsed = JSON.readValue(body, JSON_MAP);
        } catch (JacksonException e) {
            throw new KindCodecException("Invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (parsed == null) throw new KindCodecException("Top-level JSON must be an object");
        return promoteToDocument(KindHeaderCodec.unwrapJsonMeta(parsed));
    }

    private static CalendarDocument parseYaml(String body) {
        if (body.isBlank()) return CalendarDocument.empty();
        return promoteToDocument(KindHeaderCodec.parseYamlBody(body));
    }

    private static String serializeJson(CalendarDocument doc) {
        Map<String, Object> wrapped = KindHeaderCodec.wrapJsonMeta(canonicalKind(doc), buildBody(doc));
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(wrapped) + "\n";
        } catch (JacksonException e) {
            throw new KindCodecException("Failed to write JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String serializeYaml(CalendarDocument doc) {
        return KindHeaderCodec.dumpYamlBody(canonicalKind(doc), buildBody(doc));
    }

    // ── Promotion ──────────────────────────────────────────────────

    private static CalendarDocument promoteToDocument(Map<String, Object> obj) {
        Object kindRaw = obj.get("kind");
        String kind = (kindRaw instanceof String s) ? s : "";

        List<CalendarEvent> events = promoteEvents(obj.get("events"));

        Map<String, Object> extra = new LinkedHashMap<>(obj);
        extra.remove("kind");
        extra.remove("events");

        return new CalendarDocument(
                kind.isEmpty() ? "calendar" : kind,
                events,
                extra);
    }

    private static List<CalendarEvent> promoteEvents(@Nullable Object raw) {
        List<CalendarEvent> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object r : list) {
            if (!(r instanceof Map<?, ?> map)) continue;

            // title + start are the only structural requirements.
            // Events missing either are silently dropped — the input
            // is malformed but the rest of the calendar should still
            // render. (Codec stays permissive; UI surfaces the count.)
            String title = coerceToString(map.get("title"));
            if (title == null) continue;
            String start = coerceToString(map.get("start"));
            if (start == null) continue;

            String idCoerced = coerceToString(map.get("id"));
            String id = (idCoerced != null) ? idCoerced : UUID.randomUUID().toString();
            String end = coerceToString(map.get("end"));
            boolean allDay = map.get("allDay") instanceof Boolean ab && ab;
            String location = coerceToString(map.get("location"));
            List<String> attendees = promoteStringList(map.get("attendees"));
            String recurrence = coerceToString(map.get("recurrence"));
            String color = coerceToString(map.get("color"));
            List<String> tags = promoteStringList(map.get("tags"));
            String notes = coerceToString(map.get("notes"));

            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String key)) continue;
                if (isKnownEventKey(key)) continue;
                extra.put(key, e.getValue());
            }

            out.add(new CalendarEvent(
                    id, title, start, end, allDay, location, attendees,
                    recurrence, color, tags, notes, extra));
        }
        return out;
    }

    private static boolean isKnownEventKey(String key) {
        return switch (key) {
            case "id", "title", "start", "end", "allDay",
                 "location", "attendees", "recurrence",
                 "color", "tags", "notes" -> true;
            default -> false;
        };
    }

    private static List<String> promoteStringList(@Nullable Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object item : list) {
            String s = coerceToString(item);
            if (s != null) out.add(s);
        }
        return out;
    }

    /**
     * Coerce a YAML/JSON scalar value to a non-blank string. The
     * codec stores temporal values as strings so they round-trip
     * verbatim regardless of source format — but YAML's tag resolver
     * silently promotes unquoted ISO-8601 dates like
     * {@code 2026-07-15} to {@link Date}, which would otherwise cause
     * the event to be dropped. Numbers and other scalars go through
     * {@link Object#toString()}.
     *
     * <p>For {@link Date} we emit a UTC ISO-8601 string; a midnight-UTC
     * stamp (the shape SnakeYAML produces for date-only inputs) is
     * stripped to {@code yyyy-MM-dd}. That biases towards the common
     * case — users who deliberately want a literal "midnight UTC"
     * timestamp should quote the value to keep it as a string.
     */
    private static @Nullable String coerceToString(@Nullable Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s) return s.isBlank() ? null : s;
        if (raw instanceof Date d) {
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
            fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            String s = fmt.format(d);
            // SnakeYAML emits midnight UTC for unquoted date-only values
            // (yyyy-MM-dd). Strip the time portion so allDay events keep
            // a clean date-only string on round-trip.
            if (s.endsWith("T00:00:00Z") || s.endsWith("T00:00:00+00:00")) {
                return s.substring(0, 10);
            }
            return s;
        }
        String s = raw.toString();
        return s.isBlank() ? null : s;
    }

    // ── Body builder ───────────────────────────────────────────────

    private static Map<String, Object> buildBody(CalendarDocument doc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", eventsToList(doc.events()));
        for (Map.Entry<String, Object> e : doc.extra().entrySet()) {
            if (!body.containsKey(e.getKey())) body.put(e.getKey(), e.getValue());
        }
        return body;
    }

    private static List<Map<String, Object>> eventsToList(List<CalendarEvent> events) {
        List<Map<String, Object>> out = new ArrayList<>(events.size());
        for (CalendarEvent ev : events) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ev.id());
            m.put("title", ev.title());
            m.put("start", ev.start());
            if (ev.end() != null) m.put("end", ev.end());
            if (ev.allDay()) m.put("allDay", true);
            if (ev.location() != null) m.put("location", ev.location());
            if (!ev.attendees().isEmpty()) m.put("attendees", new ArrayList<>(ev.attendees()));
            if (ev.recurrence() != null) m.put("recurrence", ev.recurrence());
            if (ev.color() != null) m.put("color", ev.color());
            if (!ev.tags().isEmpty()) m.put("tags", new ArrayList<>(ev.tags()));
            if (ev.notes() != null) m.put("notes", ev.notes());
            for (Map.Entry<String, Object> ex : ev.extra().entrySet()) {
                if (!m.containsKey(ex.getKey())) m.put(ex.getKey(), ex.getValue());
            }
            out.add(m);
        }
        return out;
    }

    private static String canonicalKind(CalendarDocument doc) {
        return (doc.kind() == null || doc.kind().isBlank()) ? "calendar" : doc.kind();
    }
}
