---
triggers: was habe ich nächste woche, was steht an, kommende termine, upcoming events, what's coming up, agenda lookup, calendar query, search calendar, alle milestones, wann ist, milestone overview, lane events, who has what when, schedule overview
summary: Read-only query over a calendar-app folder. Returns events filtered by lane / tag / window. No file written — the result lives in the tool reply for the LLM to summarise back to the user.
---
# Tool — `calendar_aggregate`

Read every `kind: calendar` file under a calendar-app folder, expand recurrences inside the query window, and return a flat sorted list of occurrences. **No file is written** — this is a pure query tool.

## When to use this

- "Was hab ich nächste Woche?"
- "Welche Milestones sind im Q3?"
- "Zeig mir die Termine in Lane design"
- "Wann ist der nächste Release-Termin?"
- "Was steht alles in dem Plan?"

Whenever the LLM needs to *know* what's in a calendar-app, but not to *change* it.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `folder` | string | **yes** | App folder containing `_app.yaml` (app: calendar). |
| `from` | string | no | ISO-8601 date (`2026-06-01`). Default: 7 days before today. |
| `to` | string | no | ISO-8601 date. Default: 30 days from today. |
| `lanes` | array<string> | no | Restrict to these lane names. Empty = all. |
| `tags` | array<string> | no | Only events carrying at least one of these tags. Empty = all. |
| `expandRecurring` | boolean | no | Default `true`. Daily standups are events the user usually means when they ask "what's next week". |
| `projectId` | string | no | Default: active project. |

## Returns

```
{
  folder: "projects/website/calendars",
  app:    "calendar",
  window: { from: "2026-06-08", to: "2026-07-05" },
  eventCount: 24,
  lanes: [
    { name: "design",  eventCount: 8 },
    { name: "backend", eventCount: 12 },
    { name: "default", eventCount: 4 }
  ],
  events: [
    {
      title:      "Sprint Planning",
      start:      "2026-06-15T09:00",
      end:        "2026-06-15T11:00",
      lane:       "default",
      sourcePath: "projects/website/calendars/overview.yaml",
      location:   "Office",
      tags:       ["milestone"]
    },
    {
      title:      "Daily Standup",
      start:      "2026-06-15T09:30",
      end:        "2026-06-15T09:45",
      lane:       "default",
      sourcePath: "projects/website/calendars/overview.yaml",
      recurrence: "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20260626T000000Z"
    },
    ...
  ]
}
```

Use the `events` array to answer the user's question in natural language. Don't paste the raw JSON.

## Examples

### "Was hab ich nächste Woche?"

```
calendar_aggregate(folder="projects/website/calendars")
```

Then reply with a compact Markdown agenda:

```markdown
Nächste Woche (15.–21. Juni):

**Mo 15.** Sprint Planning 09:00–11:00 (Office), Daily Standup 09:30  
**Di 16.** Daily Standup 09:30  
**Mi 17.** Daily Standup 09:30, Design Review 14:00–16:00  
…
```

### "Welche Milestones im Q3?"

```
calendar_aggregate(
  folder="projects/website/calendars",
  from="2026-07-01",
  to="2026-09-30",
  tags=["milestone", "critical"]
)
```

Only the tagged events come back; perfect for "give me the milestones" answers.

### "Was passiert in Lane backend?"

```
calendar_aggregate(folder="projects/website/calendars", lanes=["backend"])
```

### "Wer ist wann im Urlaub?"

```
calendar_aggregate(folder="projects/team/calendars", tags=["vacation"])
```

(Assumes the user uses tags consistently — call `calendar_aggregate` once without tag filter to discover the tag vocabulary first.)

## Anti-patterns

- **Don't use `calendar_aggregate` to update events.** It's read-only. Editing goes through `doc_read` + `doc_edit` (or `calendar_create` to replace the whole calendar with new events). After edits, `app_rebuild` to refresh artifacts.
- **Don't fetch huge windows when the user only asked about "next week".** Default window (7 days back, 30 days ahead) is usually right. Narrow further if the user is specific ("just this week"), expand only when the user explicitly asks ("alle Termine bis Jahresende").
- **Don't expand recurring events when the user asked for "die wiederkehrenden Regeln".** Set `expandRecurring=false` so the raw RRULE comes back instead of 60 expanded standup occurrences.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "No _app.yaml manifest found" | Folder ist kein App-Folder | Erst `_app.yaml` anlegen oder den richtigen Folder picken |
| "Folder is a 'kanban' app, expected 'calendar'" | App-Type mismatch | Funktioniert nur in `app: calendar`-Folders |
| Leere `events`-Liste | Calendars sind leer, oder das Window enthält nichts | Window verbreitern, oder Calendars erst füllen |

## Related

- `manual_read('app-calendar')` — der ganze Calendar-App-Workflow
- `manual_read('app-rebuild')` — wenn du die generated Artefakte refreshen willst
- `manual_read('doc-kind-calendar')` — Single-Calendar-Format (für direkte Reads via `doc_read`)
