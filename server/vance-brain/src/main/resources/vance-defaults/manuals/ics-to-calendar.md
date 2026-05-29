---
triggers: ics, iCalendar, calendar invite, .ics file, ICS importieren, calendar import, Termineinladung, RFC 5545
summary: Tool to convert an iCalendar (.ics) source — typically an email attachment — into a Vance kind:calendar document the user can browse with month + agenda views.
---
# Tool — `ics_to_calendar`

Convert iCalendar (`.ics`, RFC 5545) source into a Vance `kind: calendar` document. Use this when the user has a calendar invite (email attachment, exported calendar file, or pasted ICS text) and wants it in Vance.

## When to use this

- User pastes an `.ics`-looking blob into chat ("hier ist die Einladung").
- User uploaded a `.ics` file as a Vance Document and wants it converted.
- User exports a calendar from Google Calendar / Apple Calendar / Outlook and wants a copy in Vance for searching / annotating.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `icsBody` | string | one of | Raw ICS source. Use this when the LLM has the text directly. |
| `documentRef` | string | one of | Path or id of an existing Vance Document holding the `.ics` body. |
| `title` | string | no | Title for the new calendar. Defaults to `Imported calendar` or the source document's title. |
| `outputPath` | string | no | Where to file the new calendar. Default: `calendars/<title-slug>-<timestamp>.yaml`. |
| `projectId` | string | no | Default: active project. |

Exactly one of `icsBody` / `documentRef`.

## Returns

```
{
  path:         "calendars/team-offsite-2026-05-29-103045.yaml",
  eventCount:   5,
  size:         1234,
  elapsedMs:    42,
  vanceUri:     "vance:/calendars/...",
  markdownLink: "[team-offsite-...](vance:/calendars/...)"
}
```

Paste `markdownLink` back into chat so the user can open the calendar in one click.

## Parsed fields per VEVENT

| ICS Property | → Vance Calendar field | Notes |
|---|---|---|
| `UID` | `id` | Missing UID → fresh UUID. |
| `SUMMARY` | `title` | Missing → "Untitled event". |
| `DTSTART` | `start` | `;VALUE=DATE` → all-day; otherwise date-time. |
| `DTEND` | `end` | Same. If both DT* are date-only, `allDay: true`. |
| `LOCATION` | `location` | Backslash escapes decoded. |
| `DESCRIPTION` | `notes` | Backslash escapes decoded (`\n`, `\,`, `\;`, `\\`). |
| `RRULE` | `recurrence` | Passed through verbatim. |
| `ATTENDEE` | one entry in `attendees[]` | Prefers `CN=` parameter, falls back to mailto address. |
| `CATEGORIES` | `tags[]` | Comma-split. |

**Ignored:** `VTIMEZONE` blocks, `ATTACH`, `ORGANIZER`, `X-WR-*` extensions, `TZID` parameter on DTSTART (the time is taken as-is and rendered in the viewer's local timezone). Events without a parseable `DTSTART` are skipped.

## Examples

### From an existing document

```
ics_to_calendar(documentRef="invites/team-offsite-2026-06.ics")
```

### From inline ICS body

User pasted the content, you grab it from their message:

```
ics_to_calendar(
  icsBody="BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\nUID:abc@meet\n...",
  title="Team Offsite",
  outputPath="calendars/team-offsite-q3.yaml"
)
```

### With explicit project

```
ics_to_calendar(documentRef="conf-schedule.ics", projectId="kickoff-2026")
```

## Anti-patterns

- **Don't paraphrase the ICS into a free-form YAML body by hand.** Always use this tool — it gets line-folding, escape sequences and the date-only / date-time distinction right.
- **Don't import the same ICS twice into the same calendar document.** Currently there is no merge / dedupe; you'd get two copies of every event. If you need merge, create a second calendar and tell the user.
- **Don't expect TZID support.** Times come out as local strings. Tell the user if the source has explicit timezones and you suspect drift.
- **Don't tell the user the calendar will send reminders.** Vance doesn't (see `manual_read('doc-kind-calendar')`).

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "No VEVENT blocks found" | Source isn't really ICS (truncated, base64-encoded, wrong file) | Show the user, ask them to upload the file again or paste the raw text. |
| "Source document not found" | Path/id mismatch | List documents to find the right reference. |
| Single event imported instead of expected many | Most events lacked DTSTART | Look at the source — some calendar exports use only `DTSTAMP` (creation time) without `DTSTART`. |
