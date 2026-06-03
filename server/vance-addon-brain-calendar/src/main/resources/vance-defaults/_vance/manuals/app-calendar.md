---
triggers: project plan, Projektplan, Sprint plan, Sprintplanung, gantt, gantt chart, multi calendar, mehrere kalender, calendar app, kalender app, calendars folder, projektplanung, roadmap, milestones, mehrere lanes, planning suite, sprint q3, project schedule
summary: Vance "calendar application" — a folder that turns into a planning suite with one calendar per lane, automatic Gantt + conflict generation. Use this when the user has more than one calendar to coordinate or asks for a project plan / Gantt.
---
# Application — `app: calendar`

A **Vance application folder** is a folder containing an `_app.yaml` manifest at its root. For `app: calendar` this turns the folder into a calendar-suite: one `kind: calendar` file per lane (`design.yaml`, `backend.yaml`, …) plus auto-generated `_gantt.md` and `_conflicts.yaml`.

Use this pattern when the user wants:
- A **project plan** with multiple work streams ("Sprint-Plan", "Roadmap", "Q3-Plan")
- A **Gantt visualisation** of milestones across teams / phases
- **Conflict detection** between vacations / deadlines / meetings
- Anything where a single flat `kind: calendar` file would become a monster

For a **single calendar** without lanes (just "trag morgen 14 Uhr Zahnarzt ein"), stick with the simpler `kind: calendar` document and `calendar_create` — see `manual_read('doc-kind-calendar')`.

## Folder layout

```
calendars/                  ← suite folder
├── _app.yaml               ← manifest (kind: application, app: calendar)
├── _gantt.md               ← auto-generated (kind: diagram)
├── _conflicts.yaml         ← auto-generated (kind: records)
├── overview.yaml           ← calendar, lane: "default" (root file)
├── design/
│   ├── _info.yaml          ← optional lane-local override
│   ├── mockups.yaml        ← calendar, lane: "design"
│   └── review.yaml         ← calendar, lane: "design"
└── backend/
    └── api.yaml            ← calendar, lane: "backend"
```

**Lane rule:** the **leaf folder** of a calendar's path *relative to the suite root* is its lane. Files in the root get lane `default`. Deeply nested (`a/b/c/file.yaml`) → lane = `c`.

## `_app.yaml` schema

```yaml
$meta:
  kind: application
  app: calendar
title: "Sprint Q3 Planning"
description: "Design + Backend + Frontend"

calendar:
  # Window for derived artifacts. Both bounds optional.
  window:
    from: "2026-06-01"
    until: "2026-09-30"

  # Per-lane labels / colors / ordering (all optional — auto-defaults
  # use folder name and alphabetical order).
  lanes:
    design:   { title: "Design",   color: blue,   order: 1 }
    backend:  { title: "Backend",  color: green,  order: 2 }
    frontend: { title: "Frontend", color: purple, order: 3 }

  gantt:
    outputPath: "_gantt.md"          # default
    includeRecurring: false          # default — milestones over standups
    tagFilter: []                    # empty = all
    criticalTags: [milestone, critical]
    doneTags: [done, erledigt]
    sectionOrder: [design, backend, frontend]  # explicit; remainder alpha

  conflicts:
    outputPath: "_conflicts.yaml"    # default
    ignoreWithinTags: [private]      # vacations don't conflict with each other
    ignoreAllDayOverlapsBetweenLanes: false
```

## Tool inventory

| Tool | What it does |
|---|---|
| **`calendar_app_create(folder, lanes=[…])`** | **First call for any new app.** Writes `_app.yaml` with correct schema, returns lane descriptors with `suggestedFilePath` for the follow-up `calendar_create` calls. See `manual_read('calendar-app-create')`. |
| `calendar_create(outputPath, events=[…])` | Add events into a single lane. Use `suggestedFilePath` from `calendar_app_create` as `outputPath`. |
| `app_rebuild(folder)` | **Run after lanes are populated.** Reads `_app.yaml`, regenerates `_gantt.md` + `_conflicts.yaml` in one go. Generic — works for any future Vance app type, not just calendar. |
| `calendar_aggregate(folder, from?, to?, lanes?, tags?, expandRecurring?)` | Read-only query. "Was hab ich nächste Woche?", "welche Termine in Lane backend?", "alle Milestones im Q3". Returns flat sorted event list. **No file written.** |
| `calendar_conflicts(folder, from?, to?)` | Regenerate only `_conflicts.yaml`. Use when you don't need the Gantt updated. |
| `gantt_from_calendars(folder, from?, to?)` | Regenerate only `_gantt.md`. Use when you don't need the conflicts updated. |

## Canonical flow — one-shot (preferred)

When you have the full plan up-front, **one** `calendar_app_create` call sets up everything: manifest, per-lane source files, Gantt, Conflicts.

```
calendar_app_create(
  folder="projects/website-relaunch/calendars",
  title="Website Relaunch",
  lanes=[
    { name: "design",   title: "Design",   color: "blue",   order: 1 },
    { name: "backend",  title: "Backend",  color: "green",  order: 2 },
    { name: "frontend", title: "Frontend", color: "purple", order: 3 }
  ],
  window={ from: "2026-06-01", until: "2026-09-30" },
  events=[
    # Cross-team events (no `lane:` → lane "common")
    { title: "Sprint Planning", start: "2026-06-15T09:00", end: "2026-06-15T11:00" },
    { title: "Sprint Review",   start: "2026-06-26T14:00", end: "2026-06-26T16:00", tags: ["milestone"] },

    # Per-lane events via `lane:` hint
    { title: "Mockups",     start: "2026-06-01", end: "2026-06-15", allDay: true, lane: "design",  tags: ["milestone"] },
    { title: "API design",  start: "2026-06-01", end: "2026-06-21", allDay: true, lane: "backend" },
    { title: "Beta launch", start: "2026-08-01", allDay: true,                    lane: "backend", tags: ["milestone", "critical"] }
  ]
)
# → manifest written, per-lane files written, app_rebuild ran automatically.
# Result includes artefacts[]: gantt + conflicts paths + markdownLinks.
```

**Embed both artefact `markdownLink`s in your chat reply.**

## Incremental flow — only when adding events after-the-fact

If the user wants to add events to an existing app:

1. `calendar_create(outputPath=<lane>/work.yaml, events=[…])` — replaces the lane file.
2. `app_rebuild(folder)` — refresh artefacts.

Or simpler: `calendar_app_create(overwrite=true, events=[<all events including the new ones>])` — replays the whole one-shot setup.

**Never** hand-write `_app.yaml` via `doc_create_kind` / `doc_create_text` — the schema has tripwires (kind/app/lane-shape) that `calendar_app_create` avoids.

The response contains the Gantt + conflicts paths and markdownLinks. Embed both in the chat reply:

```markdown
✓ Projektplan: [Website Relaunch](vance:/projects/website-relaunch/calendars/_app.yaml)

📊 [Gantt-Chart](vance:/projects/website-relaunch/calendars/_gantt.md) — 5 lanes, 12 milestones
⚠ [Konflikt-Übersicht](vance:/projects/website-relaunch/calendars/_conflicts.yaml) — 2 Konflikte
```

## Anti-patterns

- **Don't put all events into a single calendar file** when the user has natural lane separation (teams, phases, work streams). The whole point of the app pattern is editability per lane.
- **Don't include recurring events** in the Gantt (daily standups blow it up). Default behaviour (`includeRecurring: false`) drops them; if the user explicitly wants them visible, change the manifest, not the recurring event.
- **Don't write `_gantt.md` or `_conflicts.yaml` by hand.** They are regenerated artifacts. Hand-edits get overwritten on next `app_rebuild`. Edit the source calendars, then rebuild.
- **Don't call `app_rebuild` after every single tiny edit.** It's cheap but not free. Once at the end of a planning session is enough.
- **Don't forget to embed both artifact `markdownLink`s in chat.** That's where the user sees the result.

## When NOT to use this pattern

- **Single calendar without lanes** → `kind: calendar` + `calendar_create`. App pattern is overhead.
- **Free-form task list** → `kind: list` or `kind: records`. Calendar implies time anchors.
- **Project management with task dependencies and resource allocation** → that's not Vance's scope. Recommend exporting to Linear / Jira / GitHub Projects.
