---
triggers: calendar export, ics export, kalender exportieren, ics file, .ics datei, in google calendar, in outlook, in apple calendar, in iCloud calendar, send to phone calendar, calendar herunterladen, Kalender für Outlook, calendar to google
summary: Tool that exports a Vance kind:calendar document as an .ics file the user can import into Google Calendar / Apple Calendar / Outlook / any CalDAV client.
---
# Tool — `calendar_export_ics`

Export an existing Vance calendar to an iCalendar (`.ics`, RFC 5545) file. Use this when the user wants their Vance-managed events to **also** live in their personal calendar app — Google Calendar, Apple Calendar, Outlook, Mozilla Thunderbird, anything CalDAV-aware.

## When to use this

- "Exportier den Sprint-Plan als .ics."
- "Ich will die Termine in meinem iPhone-Kalender haben."
- "Hänge das als Calendar-Datei an die Email."
- "Schick mir das als Outlook-Termin."

For **single events** consider pointing the user at the per-event "Add to Calendar" buttons in the Calendar view first — they need no download and open Google / Outlook directly. Use this tool when the user wants the **whole calendar** in one file.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `documentRef` | string | **yes** | Path or id of a `kind: calendar` document. |
| `calendarName` | string | no | Display name embedded as `X-WR-CALNAME`. Apple Calendar and Evolution show it; Google ignores it. Defaults to the source document's title. |
| `outputPath` | string | no | Where to file the new `.ics`. Default: `exports/<calendar-slug>-<timestamp>.ics`. |
| `projectId` | string | no | Default: active project. |

## Returns

```
{
  path:         "exports/sprint-q3-2026-05-29-104500.ics",
  eventCount:   5,
  size:         2348,
  elapsedMs:    12,
  vanceUri:     "vance:/exports/...",
  markdownLink: "[sprint-q3-...](vance:/exports/...)"
}
```

Paste `markdownLink` into chat. The user clicks → browser downloads the `.ics` → calendar app prompts "import 5 events?".

## Roundtrip behaviour

- **Timed events**: `2026-06-12T09:00:00+02:00` → `20260612T070000Z` (normalised to UTC). Local form without offset (`2026-06-12T09:00`) → floating local time in the `.ics`, interpreted in the user's calendar zone on import.
- **All-day events**: emitted with `;VALUE=DATE`. RFC 5545 requires `DTEND` to be **exclusive** — so a stored `end: "2026-07-28"` lands as `DTEND;VALUE=DATE:20260729` in the `.ics`. Calendar apps draw 15-28 correctly.
- **Recurrence**: RRULE passes through verbatim (`FREQ=WEEKLY;BYDAY=MO` etc.). A leading `RRULE:` prefix in the source is stripped, since the wire format doesn't want it twice.
- **Attendees**: anything that looks like an email becomes `ATTENDEE:mailto:…`. Plain names become `ATTENDEE;CN=Name:invalid:nomail` so Google/Outlook still accept the line.
- **Categories**: comma-joined tags into `CATEGORIES:`.

## Anti-patterns

- **Don't re-export after every edit.** Most calendar apps merge by UID — duplicates won't appear, but the same event may "flicker" between values during re-import. Tell the user this is a snapshot.
- **Don't promise continuous sync.** It's one-shot. Continuous sync needs a subscription URL endpoint (planned v2).
- **Don't use this for kinds other than `calendar`.** The tool rejects non-`kind: calendar` sources.
- **Don't try to import the export back into the same calendar.** It would duplicate every event under the same UIDs.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "Source document has kind 'X', expected kind:calendar" | User asked to export a non-calendar doc | Make a `calendar_create` first, then export. |
| "Calendar has no events to export" | Empty calendar | Add events first. |
