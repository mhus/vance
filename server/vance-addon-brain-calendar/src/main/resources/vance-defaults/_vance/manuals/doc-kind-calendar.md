---
triggers: calendar, Kalender, Termine, appointments, meeting, Meetings, schedule, Zeitplan, Sprint plan, deadline, Deadline, RRULE, recurring, wiederkehrend, agenda, Urlaubsplan, vacation, holidays, events, Veranstaltungen
summary: Vance Document Kind for a flat list of events — meetings, deadlines, recurring activities — rendered as a month grid + agenda. Read-only; YAML/JSON only.
---
# Document Kind — `calendar`

Use this kind when the user wants a **time-anchored list of events**: meetings, sprint plan, conference schedule, vacation plan, deadlines, recurring activities. The Web UI renders both an agenda view (chronological list) and a month grid; in chat, only the agenda for the next 14 days is shown inline.

## When to pick `calendar` over other kinds

| Picking … | When |
|---|---|
| `calendar` | Events with **dates and times**, possibly recurring, possibly all-day. Anything that lives on a calendar app. |
| `records` | Tabular structured data **without** a primary time axis (people lists, line items, settings). |
| `tree` / `mindmap` | Hierarchical brainstorming, decision trees. No time axis. |
| `list` | Simple bullet list without per-item structure. |
| `graph` | Nodes + edges (relationships), no timeline. |

If unsure: does the user ask "wann?" or "wieviel Zeit?" → `calendar`. Asks "was?" or "welche?" → `records` / `list`.

## Vance is not a calendar backend

There are **no** reminders, **no** notifications, **no** free/busy solver, **no** invite sending. We render and search; the user exports to Google/Apple Calendar if they need pushes. Don't promise functionality Vance doesn't have.

## Format — YAML/JSON only

Markdown is **not supported** for `calendar`. Always create with `mimeType: application/yaml` (preferred) or `application/json`. If a user asks for "MD calendar", politely point them at YAML.

## Schema

```yaml
$meta:
  kind: calendar
events:
  - id: <stable-id>            # optional; auto-filled with UUID if missing
    title: <required string>
    start: <required ISO-8601> # "2026-06-12T09:00" or "2026-06-12" (all-day)
    end: <ISO-8601>            # optional
    allDay: <true|false>       # optional, default false
    location: <free string>    # optional
    attendees: [<name>, ...]   # optional
    recurrence: <RRULE>        # optional, RFC 5545 string
    color: <palette|css>       # optional
    tags: [<tag>, ...]         # optional
    notes: <multiline string>  # optional
```

Hard requirements per event: `title` + `start`. Everything else optional. Events missing either are silently dropped on parse.

## Recurrence (RFC 5545 RRULE)

Recurrence lives in **one** field: `recurrence: <RRULE string>`. Subset the renderer expands inline (v1):

| Field | Values | Example |
|---|---|---|
| `FREQ` | `DAILY` / `WEEKLY` / `MONTHLY` / `YEARLY` | `FREQ=WEEKLY` |
| `INTERVAL` | integer ≥ 1 | `INTERVAL=2` (every other week) |
| `BYDAY` (WEEKLY only) | comma list of `MO,TU,WE,TH,FR,SA,SU` | `BYDAY=MO,WE,FR` |
| `UNTIL` | `yyyymmdd` or `yyyymmddTHHmmssZ` | `UNTIL=20261231T000000Z` |
| `COUNT` | integer (alternative to UNTIL) | `COUNT=10` |

Common patterns:

```yaml
# Every weekday until end of year
recurrence: FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20261231T000000Z

# Every other Monday for 10 occurrences
recurrence: FREQ=WEEKLY;BYDAY=MO;INTERVAL=2;COUNT=10

# Monthly on the date of the first occurrence
recurrence: FREQ=MONTHLY;COUNT=12

# Daily for 2 weeks
recurrence: FREQ=DAILY;COUNT=14
```

**Always provide `UNTIL` or `COUNT`** — otherwise the renderer caps at 500 occurrences (hardstop) and the user sees a truncated view without knowing why. Pick a horizon.

## Colors (palette)

Recognised palette names (rendered as fixed hex): `blue`, `green`, `red`, `orange`, `yellow`, `purple`, `pink`, `teal`, `gray`. Anything else is passed through as a CSS color (so `color: "#ff8800"` works too).

Use colors **semantically** to help the user scan: `blue` for work, `green` for milestones, `gray` for recurring routine, `orange` for time-off. Don't randomise.

## Creating a calendar — `calendar_create` is the canonical tool

**Always prefer `calendar_create` over `doc_create_kind(kind="calendar", body=…)`.** It takes a typed event list, builds the YAML internally, and returns a `markdownLink` for chat. Schema is the same as documented above (one object per event in the `events` array).

```
calendar_create(
  events=[
    {
      "title": "Sprint Planning",
      "start": "2026-06-15T09:00",
      "end":   "2026-06-15T11:00",
      "location": "Office",
      "color": "blue"
    },
    {
      "title": "Daily Standup",
      "start": "2026-06-15T09:30",
      "end":   "2026-06-15T09:45",
      "recurrence": "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20260626T000000Z",
      "color": "gray"
    },
    {
      "title": "Sprint Review",
      "start": "2026-06-26T14:00",
      "end":   "2026-06-26T16:00",
      "color": "green"
    }
  ],
  title="Sprint Q3",
  outputPath="calendars/sprint-q3.yaml"
)
```

Returns `{ path, eventCount, vanceUri, markdownLink, addLinks }`. Embed `markdownLink` in your answer so the user opens the calendar with one click.

### `addLinks` — one-click push to the user's real calendar

The response also carries an `addLinks` array, one entry per event in the same order as your input, with the shape:

```
[
  { "title": "Sprint Planning", "google": "https://calendar.google.com/calendar/render?...", "outlook": "https://outlook.live.com/calendar/0/deeplink/compose?..." },
  ...
]
```

**Always embed these in your chat reply** when the user asked you to *put something into the calendar* / *book a slot* / *trag X ein*. Pattern:

```markdown
Habe deinen Sprint-Plan vorbereitet: [sprint-q3](vance:/calendars/sprint-q3.yaml)

- **Sprint Planning** Mo 15.06. 09:00-11:00 — [→ Google](google-url) · [→ Outlook](outlook-url)
- **Daily Standup** Mo-Fr (recurring) — [→ Google](google-url) · [→ Outlook](outlook-url)
- **Sprint Review** Fr 26.06. 14:00 — [→ Google](google-url) · [→ Outlook](outlook-url)
```

That's the Vance Calendar workflow: **you draft, the user one-clicks into their real calendar app**. Vance is the assistant, not the calendar. For Apple/iCloud users the per-event 📅 button in the Web UI's Calendar view carries an .ics download.

For a single quick-add ("trag morgen 14 Uhr Zahnarzt ein") the same tool with one event works — the user gets one Google + one Outlook link, picks the one they use, and the event lands in their real calendar in one click.

If you absolutely need the low-level path (e.g. to attach unusual `extra` fields the typed tool doesn't expose), `doc_create_kind(kind="calendar", mimeType="application/yaml", body="$meta:\n  kind: calendar\n...")` still works.

## Importing an `.ics` file

If the user uploaded a calendar invite (`.ics` attachment from an email), call `ics_to_calendar` (see `manual_read('ics-to-calendar')`):

```
ics_to_calendar(documentRef="invites/team-offsite.ics")
```

The result is a fresh `kind: calendar` document with one event per VEVENT.

## Exporting to Google / Apple / Outlook

For **single events** the Calendar view in the Web UI shows a 📅 button next to each event with one-click links to Google Calendar and Outlook plus an `.ics` download — no tool call needed; just tell the user where the button is.

For **the whole calendar** call `calendar_export_ics` (see `manual_read('calendar-export-ics')`):

```
calendar_export_ics(documentRef="calendars/sprint-q3.yaml")
```

Returns a `.ics` file the user imports into their calendar app. One-shot, not a continuous sync.

## Editing — full-body rewrites in v1

There are **no** `calendar_add_event` / `calendar_remove_event` granular tools yet. To add or change events, read the calendar with `doc_read`, modify the YAML, and write the whole body back with `doc_edit`. Yes, this means re-emitting the entire events list each time — that's the v1 trade-off.

## Anti-patterns

- **Don't use `markdown` mime type.** It's rejected by the codec.
- **Don't omit `UNTIL`/`COUNT` on recurring events.** Hardstop at 500 occurrences leaves the user confused.
- **Don't write Markdown tables and call them a "calendar".** That's `records`.
- **Don't try to model task dependencies / Gantt-bars.** Wrong kind — Vance doesn't do project planning. Push back politely.
- **Don't promise reminders or push notifications.** Vance is read/search only.
- **Don't include time in `start` when `allDay: true`.** `start: "2026-07-15"` (date-only) for full-day events; `start: "2026-07-15T09:00"` for timed ones.
- **Don't use unquoted ISO dates in YAML.** Some YAML parsers coerce `2026-07-15` to a `Date` object. Always quote: `start: "2026-07-15"`. The Vance codec auto-coerces but other tools may not.
- **Don't invent your own recurrence DSL.** RRULE only.
