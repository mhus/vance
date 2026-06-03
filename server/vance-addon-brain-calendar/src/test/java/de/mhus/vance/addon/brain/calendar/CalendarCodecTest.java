package de.mhus.vance.addon.brain.calendar;

import de.mhus.vance.shared.document.kind.KindCodecException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trip and edge-case behaviour for {@code kind: calendar}.
 *
 * <p>The codec does not parse the date/time or RRULE strings — those
 * are pass-through. The tests therefore assert wrapper structure
 * (events list, extras, allDay flag) plus the few invariants the
 * codec does enforce: {@code title} + {@code start} required, missing
 * {@code id} auto-filled, malformed events silently dropped.
 */
class CalendarCodecTest {

    private static final String JSON_MIME = "application/json";
    private static final String YAML_MIME = "application/yaml";

    // ── parse: JSON happy paths ───────────────────────────────────

    @Test
    void parseJson_basicEvent() {
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [
                    {
                      "id": "abc",
                      "title": "Sprint Planning",
                      "start": "2026-06-12T09:00",
                      "end": "2026-06-12T11:00",
                      "location": "Büro"
                    }
                  ]
                }
                """;

        CalendarDocument doc = CalendarCodec.parse(body, JSON_MIME);

        assertThat(doc.kind()).isEqualTo("calendar");
        assertThat(doc.events()).hasSize(1);
        CalendarEvent ev = doc.events().get(0);
        assertThat(ev.id()).isEqualTo("abc");
        assertThat(ev.title()).isEqualTo("Sprint Planning");
        assertThat(ev.start()).isEqualTo("2026-06-12T09:00");
        assertThat(ev.end()).isEqualTo("2026-06-12T11:00");
        assertThat(ev.location()).isEqualTo("Büro");
        assertThat(ev.allDay()).isFalse();
    }

    @Test
    void parseJson_allDayEvent() {
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [
                    {
                      "id": "vacation-1",
                      "title": "Urlaub Frankreich",
                      "start": "2026-07-15",
                      "end": "2026-07-28",
                      "allDay": true,
                      "tags": ["private", "travel"]
                    }
                  ]
                }
                """;

        CalendarDocument doc = CalendarCodec.parse(body, JSON_MIME);
        CalendarEvent ev = doc.events().get(0);

        assertThat(ev.allDay()).isTrue();
        assertThat(ev.tags()).containsExactly("private", "travel");
    }

    @Test
    void parseJson_recurringEvent_rrulePassedThroughVerbatim() {
        String rrule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20261231T000000Z";
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [
                    {
                      "id": "standup",
                      "title": "Daily Standup",
                      "start": "2026-06-01T09:00",
                      "end": "2026-06-01T09:15",
                      "recurrence": "%s"
                    }
                  ]
                }
                """.formatted(rrule);

        CalendarDocument doc = CalendarCodec.parse(body, JSON_MIME);
        assertThat(doc.events().get(0).recurrence()).isEqualTo(rrule);
    }

    @Test
    void parseJson_missingId_isAutoFilledWithUuid() {
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [
                    { "title": "Mystery meeting", "start": "2026-06-12T14:00" }
                  ]
                }
                """;

        CalendarDocument doc = CalendarCodec.parse(body, JSON_MIME);
        String id = doc.events().get(0).id();
        assertThat(id).isNotBlank();
        // UUID v4: 36 chars with 4 dashes
        assertThat(id).hasSize(36);
        assertThat(id.chars().filter(c -> c == '-').count()).isEqualTo(4);
    }

    @Test
    void parseJson_missingTitle_eventSilentlyDropped() {
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [
                    { "start": "2026-06-12T09:00" },
                    { "title": "OK Event", "start": "2026-06-12T10:00" }
                  ]
                }
                """;

        CalendarDocument doc = CalendarCodec.parse(body, JSON_MIME);
        assertThat(doc.events()).hasSize(1);
        assertThat(doc.events().get(0).title()).isEqualTo("OK Event");
    }

    @Test
    void parseJson_missingStart_eventSilentlyDropped() {
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [
                    { "title": "Floating event" },
                    { "title": "Anchored event", "start": "2026-06-12" }
                  ]
                }
                """;

        CalendarDocument doc = CalendarCodec.parse(body, JSON_MIME);
        assertThat(doc.events()).hasSize(1);
        assertThat(doc.events().get(0).title()).isEqualTo("Anchored event");
    }

    @Test
    void parseJson_eventOrderPreserved() {
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [
                    { "id": "z", "title": "Z first", "start": "2026-07-01" },
                    { "id": "a", "title": "A second", "start": "2026-06-01" }
                  ]
                }
                """;

        CalendarDocument doc = CalendarCodec.parse(body, JSON_MIME);
        assertThat(doc.events()).extracting(CalendarEvent::id).containsExactly("z", "a");
    }

    @Test
    void parseJson_unknownEventField_landsInEventExtra() {
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [
                    {
                      "id": "x",
                      "title": "Event",
                      "start": "2026-06-12",
                      "futureField": { "v": 1 }
                    }
                  ]
                }
                """;

        CalendarDocument doc = CalendarCodec.parse(body, JSON_MIME);
        assertThat(doc.events().get(0).extra()).containsKey("futureField");
    }

    @Test
    void parseJson_unknownTopLevelField_landsInDocumentExtra() {
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [],
                  "subscriptionUrl": "https://example.com/cal.ics"
                }
                """;

        CalendarDocument doc = CalendarCodec.parse(body, JSON_MIME);
        assertThat(doc.extra()).containsEntry("subscriptionUrl", "https://example.com/cal.ics");
    }

    @Test
    void parseJson_emptyBody_yieldsEmptyDocument() {
        CalendarDocument doc = CalendarCodec.parse("", JSON_MIME);
        assertThat(doc.kind()).isEqualTo("calendar");
        assertThat(doc.events()).isEmpty();
    }

    @Test
    void parseJson_invalidJson_throwsKindCodecException() {
        assertThatThrownBy(() -> CalendarCodec.parse("{ not json", JSON_MIME))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Invalid JSON");
    }

    // ── parse: YAML ───────────────────────────────────────────────

    @Test
    void parseYaml_basicEvents() {
        String body = """
                $meta:
                  kind: calendar
                events:
                  - id: e1
                    title: Sprint Planning
                    start: 2026-06-12T09:00
                    end: 2026-06-12T11:00
                  - id: e2
                    title: Urlaub
                    start: 2026-07-15
                    end: 2026-07-28
                    allDay: true
                """;

        CalendarDocument doc = CalendarCodec.parse(body, YAML_MIME);

        assertThat(doc.events()).hasSize(2);
        assertThat(doc.events().get(0).title()).isEqualTo("Sprint Planning");
        assertThat(doc.events().get(1).allDay()).isTrue();
    }

    @Test
    void parseYaml_attendeesAndTags_asLists() {
        String body = """
                $meta:
                  kind: calendar
                events:
                  - id: e1
                    title: Review
                    start: 2026-06-12T14:00
                    attendees: [alice, bob, carol]
                    tags: [work, important]
                """;

        CalendarDocument doc = CalendarCodec.parse(body, YAML_MIME);
        CalendarEvent ev = doc.events().get(0);
        assertThat(ev.attendees()).containsExactly("alice", "bob", "carol");
        assertThat(ev.tags()).containsExactly("work", "important");
    }

    // ── serialize ─────────────────────────────────────────────────

    @Test
    void serializeJson_canonicalShape() {
        CalendarEvent ev = new CalendarEvent(
                "abc", "Sprint Planning",
                "2026-06-12T09:00", "2026-06-12T11:00",
                false, "Büro", List.of("alice", "bob"),
                null, "blue", List.of(), null, new LinkedHashMap<>());
        CalendarDocument doc = new CalendarDocument(
                "calendar", List.of(ev), new LinkedHashMap<>());

        String out = CalendarCodec.serialize(doc, JSON_MIME);

        assertThat(out).contains("\"$meta\"");
        assertThat(out).contains("\"kind\" : \"calendar\"");
        assertThat(out).contains("\"events\"");
        assertThat(out).contains("\"title\" : \"Sprint Planning\"");
        // allDay false is omitted (compact on-disk shape)
        assertThat(out).doesNotContain("\"allDay\"");
    }

    @Test
    void serializeJson_allDayTrue_isEmitted() {
        CalendarEvent ev = new CalendarEvent(
                "v1", "Urlaub", "2026-07-15", "2026-07-28",
                true, null, List.of(), null, null, List.of(),
                null, new LinkedHashMap<>());
        CalendarDocument doc = new CalendarDocument(
                "calendar", List.of(ev), new LinkedHashMap<>());

        String out = CalendarCodec.serialize(doc, JSON_MIME);
        assertThat(out).contains("\"allDay\" : true");
    }

    @Test
    void serializeJson_emptyEvents_stillEmitsEventsKey() {
        CalendarDocument doc = CalendarDocument.empty();
        String out = CalendarCodec.serialize(doc, JSON_MIME);
        assertThat(out).contains("\"events\"");
    }

    @Test
    void serializeYaml_canonicalShape() {
        CalendarEvent ev = new CalendarEvent(
                "abc", "Meeting", "2026-06-12T09:00", null,
                false, null, List.of(), null, null, List.of(),
                null, new LinkedHashMap<>());
        CalendarDocument doc = new CalendarDocument(
                "calendar", List.of(ev), new LinkedHashMap<>());

        String out = CalendarCodec.serialize(doc, YAML_MIME);
        assertThat(out).contains("$meta:");
        assertThat(out).contains("kind: calendar");
        assertThat(out).contains("events:");
        assertThat(out).contains("title: Meeting");
    }

    // ── round-trips ───────────────────────────────────────────────

    @Test
    void roundTrip_json_preservesAllFields() {
        String body = """
                {
                  "$meta": { "kind": "calendar" },
                  "events": [
                    {
                      "id": "standup",
                      "title": "Daily Standup",
                      "start": "2026-06-01T09:00",
                      "end": "2026-06-01T09:15",
                      "allDay": false,
                      "location": "Office",
                      "attendees": ["alice", "bob"],
                      "recurrence": "FREQ=WEEKLY;BYDAY=MO,WE",
                      "color": "blue",
                      "tags": ["work"],
                      "notes": "team sync"
                    }
                  ]
                }
                """;

        CalendarDocument first = CalendarCodec.parse(body, JSON_MIME);
        String written = CalendarCodec.serialize(first, JSON_MIME);
        CalendarDocument second = CalendarCodec.parse(written, JSON_MIME);

        CalendarEvent ev = second.events().get(0);
        assertThat(ev.id()).isEqualTo("standup");
        assertThat(ev.title()).isEqualTo("Daily Standup");
        assertThat(ev.start()).isEqualTo("2026-06-01T09:00");
        assertThat(ev.end()).isEqualTo("2026-06-01T09:15");
        assertThat(ev.location()).isEqualTo("Office");
        assertThat(ev.attendees()).containsExactly("alice", "bob");
        assertThat(ev.recurrence()).isEqualTo("FREQ=WEEKLY;BYDAY=MO,WE");
        assertThat(ev.color()).isEqualTo("blue");
        assertThat(ev.tags()).containsExactly("work");
        assertThat(ev.notes()).isEqualTo("team sync");
    }

    @Test
    void roundTrip_yaml_preservesAllDayAndExtras() {
        String body = """
                $meta:
                  kind: calendar
                events:
                  - id: v
                    title: Urlaub
                    start: 2026-07-15
                    end: 2026-07-28
                    allDay: true
                    tags: [private]
                subscriptionUrl: https://example.com/cal.ics
                """;

        CalendarDocument first = CalendarCodec.parse(body, YAML_MIME);
        String written = CalendarCodec.serialize(first, YAML_MIME);
        CalendarDocument second = CalendarCodec.parse(written, YAML_MIME);

        assertThat(second.events().get(0).allDay()).isTrue();
        assertThat(second.events().get(0).tags()).containsExactly("private");
        assertThat(second.extra()).containsEntry("subscriptionUrl", "https://example.com/cal.ics");
    }

    // ── defensive ─────────────────────────────────────────────────

    @Test
    void parse_unsupportedMime_throws() {
        assertThatThrownBy(() -> CalendarCodec.parse("anything", "text/markdown"))
                .isInstanceOf(KindCodecException.class)
                .hasMessageContaining("Unsupported mime type");
    }

    @Test
    void supports_recognisesMimeFamily() {
        assertThat(CalendarCodec.supports("application/json")).isTrue();
        assertThat(CalendarCodec.supports("application/yaml")).isTrue();
        assertThat(CalendarCodec.supports("text/yaml")).isTrue();
        assertThat(CalendarCodec.supports("text/markdown")).isFalse();
        assertThat(CalendarCodec.supports(null)).isFalse();
    }
}
