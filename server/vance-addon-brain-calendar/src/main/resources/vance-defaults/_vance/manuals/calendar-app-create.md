---
triggers: calendar app create, sprint plan anlegen, projektplan anlegen, multi calendar setup, lanes anlegen, planning suite setup, new calendar app, neue kalender app, kalender app einrichten, projekt aufsetzen, sprint q3, roadmap aufsetzen
summary: Bootstrap a calendar-suite application — writes the _app.yaml manifest with correct schema and returns lane suggestedFilePath hints. Always use this BEFORE calling calendar_create for multi-lane plans.
---
# Tool — `calendar_app_create`

Bootstrap a new calendar-suite application folder. **First call** whenever the user wants a multi-lane project plan / sprint plan / Gantt-style layout.

## Why this tool exists

The manifest format is small but easy to get wrong:
- `$meta.kind` MUST be `application` (LLMs frequently omit this).
- Lanes are a Map keyed by lane-name, NOT an array.
- The whole config lives under `calendar:` (NOT flat at the manifest root).
- Sub-folder convention: lane = sub-folder name.

`calendar_app_create` builds the manifest server-side from typed parameters so none of those can go wrong, and returns each lane with a ready-to-use `suggestedFilePath` for the follow-up `calendar_create` calls.

## When to use this

- "Lege einen Sprint-Plan an mit Lanes Design / Backend / Frontend"
- "Mach mir einen Q3-Projektplan"
- "Roadmap mit drei Tracks aufsetzen"
- "Initial setup für einen Multi-Calendar-Plan"

**Don't** use this for single-event calendars without lanes — use `calendar_create` directly.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `folder` | string | **yes** | Folder for the new app. `_app.yaml` lands at `<folder>/_app.yaml`. |
| `title` | string | no | Display title. |
| `description` | string | no | Free description. |
| `lanes` | array<Lane> | no¹ | Each: `{ name, title?, color?, order? }`. Order in the array = render order in the Gantt. |
| **`events`** | **array<Event>** | **no²** | **One-shot:** pass the full event list here and the tool writes per-lane files + auto-rebuilds. Each event: `{ title, start, end?, allDay?, lane?, recurrence?, tags?, ... }`. Events without `lane` land in lane `common`. |
| `window` | object | no | `{ from?: ISO-date, until?: ISO-date }` — render bounds for the Gantt. |
| `overwrite` | boolean | no | Default false. Allow replacing an existing `_app.yaml`. |
| `projectId` | string | no | Default: active project. |

¹ Technically optional, but a calendar-app without lanes is just a single-calendar use case — push back and ask which lanes the user wants. Lanes referenced by `events` but missing from `lanes` are auto-added with defaults.

² **Strongly recommended.** Passing events here turns this into a single-call setup. Without events, you have to chain N × `calendar_create` + `app_rebuild` — more failure modes for the LLM.

## Returns

When called with `events`, the tool returns the manifest, the per-lane file structure, **and** the artefacts (Gantt + Conflicts) from the auto-refresh that ran at the end:

```json
{
  "app": "calendar",
  "folder": "projects/website/calendars",
  "manifestPath": "projects/website/calendars/_app.yaml",
  "markdownLink": "[Calendar app](vance:/...)",
  "lanes": [
    { "name": "design",  "title": "Design",  "color": "blue",  "suggestedFilePath": "..." },
    { "name": "backend", "title": "Backend", "color": "green", "suggestedFilePath": "..." }
  ],
  "artefacts": [
    { "name": "conflicts", "path": ".../calendars/_conflicts.yaml",
      "markdownLink": "[Calendar conflicts](vance:/...)",
      "stats": { "conflictCount": 1, "eventCount": 14 } },
    { "name": "gantt", "path": ".../calendars/_gantt.md",
      "markdownLink": "[Gantt](vance:/...)",
      "stats": { "eventCount": 4, "laneCount": 3 } }
  ],
  "nextStep": "App is ready — Gantt + Conflicts are in the `artefacts` list..."
}
```

Without `events`, no auto-refresh runs and `artefacts` is empty — you'd then chain `calendar_create` calls + `app_rebuild` manually. **Prefer the one-shot form.**

**Always embed both artefact `markdownLink`s in your chat reply** so the user can open the Gantt and the Conflicts table with one click.

## Canonical flow — one-shot (preferred)

```
calendar_app_create(
  folder="projects/website/calendars",
  title="Website Relaunch",
  lanes=[
    { name: "design",  title: "Design",  color: "blue",   order: 1 },
    { name: "backend", title: "Backend", color: "green",  order: 2 },
    { name: "frontend", title: "Frontend", color: "purple", order: 3 }
  ],
  window={ from: "2026-06-01", until: "2026-09-30" },
  events=[
    # Cross-team events — no `lane:` → land in lane "common"
    { title: "Sprint Planning", start: "2026-06-15T09:00", end: "2026-06-15T11:00" },
    { title: "Daily Standup", start: "2026-06-15T09:30", end: "2026-06-15T09:45",
      recurrence: "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20260626T235959Z" },
    { title: "Sprint Review", start: "2026-06-26T14:00", end: "2026-06-26T16:00", tags: ["milestone"] },
    { title: "Retro",         start: "2026-06-26T16:00", end: "2026-06-26T17:00" },

    # Team-specific events — lane: hint dispatches to the right file
    { title: "Mockups",     start: "2026-06-01", end: "2026-06-15", allDay: true,
      lane: "design", tags: ["milestone"] },
    { title: "Review",      start: "2026-06-16", end: "2026-06-18", allDay: true, lane: "design" },
    { title: "API design",  start: "2026-06-01", end: "2026-06-21", allDay: true, lane: "backend" },
    { title: "Beta launch", start: "2026-08-01", allDay: true, lane: "backend",
      tags: ["milestone", "critical"] }
  ]
)
# → returns manifest + lanes + artefacts (gantt + conflicts) in one result
```

That's it. One call, full setup. The result's `artefacts` array carries the Gantt and Conflicts paths to embed in chat.

## Incremental flow — only when adding events later

After the app exists, additional events go via `calendar_create` + `app_rebuild`:

```
calendar_create(
  outputPath="projects/website/calendars/backend/work.yaml",   # already exists; will merge? NO — replaces.
  events=[ ... ]
)
app_rebuild(folder="projects/website/calendars")
```

**Heads-up:** `calendar_create` replaces the target file's events. To *append* one event, use `doc_read` → modify YAML → `doc_edit`, or simply add to the next `calendar_app_create(overwrite=true, events=[…])` payload.

## Anti-patterns

- **Don't write `_app.yaml` yourself via `doc_create_kind` / `doc_create_text`.** The schema has too many tripwires; use this tool.
- **Don't chain `calendar_app_create` + N × `calendar_create` + `app_rebuild` when you have all the events up front.** Pass them in `events` to `calendar_app_create` directly — one call, no orchestration drift.
- **Don't call `calendar_create` with empty `events: []`** to "init a lane". Lanes exist via the manifest; no placeholder file needed. The tool now returns a `skipped: true` warning instead of an error, but it's still wasted calls.
- **Don't put calendar files directly under the app folder** (e.g. `calendars/sprint-q3/design.yaml` instead of `calendars/sprint-q3/design/work.yaml`). Files in the app root land in the auto-default lane `default` — not in any lane you defined. The `suggestedFilePath` from this tool steers you right.
- **Don't pass `lanes: []`** unless the user really only has one stream — and in that case skip the app pattern entirely.
- **Don't reuse a folder for two apps.** Each app folder hosts exactly one `_app.yaml`.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "Manifest already exists at ..." | The folder is already an app | Pick a different folder, or pass `overwrite=true` if the user wants a fresh start |
| Lane name gets sanitised | Lane name had spaces / special chars | The returned `lanes[].name` is the actual sanitised form — use that, not your original input, for any further references |

## Related

- `manual_read('app-calendar')` — full calendar-app workflow + manifest schema
- `manual_read('app-rebuild')` — regeneration of `_gantt.md` + `_conflicts.yaml`
- `manual_read('doc-kind-calendar')` — single-calendar format (used inside each lane)
