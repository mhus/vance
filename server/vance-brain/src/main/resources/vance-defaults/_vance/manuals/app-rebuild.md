---
triggers: app_rebuild, regenerate gantt, refresh gantt, update gantt, regenerate conflicts, regenerate plan, neu generieren, projektplan aktualisieren, refresh project, rebuild app, kalender neu bauen, gantt aktualisieren, plan refresh
summary: Generic refresh tool for any Vance application folder. Reads _app.yaml, dispatches to the right per-app service, regenerates all derived artifacts (Gantt + conflicts for app:calendar; later boards for app:kanban, indexes for app:wiki, etc.).
---
# Tool — `app_rebuild`

Regenerate every derived artifact in a Vance application folder. The tool is **generic** — it reads `_app.yaml`, looks at `$meta.app`, and routes to the matching Java service (`CalendarsApplication` for `app: calendar`, future `KanbanApplication` for `app: kanban`, …).

## When to use this

Whenever the user has changed something inside a calendar / kanban / wiki app and wants the views updated:

- "Update the Gantt chart"
- "Regenerate the conflict list"
- "Refresh the project plan"
- "Build the app artifacts"
- After any sequence of `calendar_create` / `doc_edit` calls inside an app folder

For one-off changes the user can keep editing source files; the artifacts only need to be regenerated when the user wants to see the result.

## Parameters

| Param | Type | Required | Notes |
|---|---|---|---|
| `folder` | string | **yes** | Path to the app folder containing `_app.yaml`. |
| `projectId` | string | no | Default: active project. |

## Returns

```
{
  app:          "calendar",
  folder:       "projects/website/calendars",
  artefactCount: 2,
  artefacts: [
    {
      name:         "conflicts",
      path:         "projects/website/calendars/_conflicts.yaml",
      markdownLink: "[_conflicts.yaml](vance:/...)",
      stats:        { conflictCount: 2, eventCount: 18, from: ..., to: ... }
    },
    {
      name:         "gantt",
      path:         "projects/website/calendars/_gantt.md",
      markdownLink: "[_gantt.md](vance:/...)",
      stats:        { eventCount: 12, laneCount: 3, ... }
    }
  ]
}
```

**Always embed both `markdownLink`s in your chat reply** so the user can open both artifacts with one click:

```markdown
✓ Project plan updated:

📊 [Gantt chart](vance:/projects/website/calendars/_gantt.md) — 12 events in 3 lanes
⚠ [Conflict overview](vance:/projects/website/calendars/_conflicts.yaml) — 2 conflicts
```

## Use this — not the granular tools — by default

For `app: calendar` there are also:

- `calendar_conflicts(folder)` — refresh only `_conflicts.yaml`
- `gantt_from_calendars(folder)` — refresh only `_gantt.md`

These exist as special-purpose tools for when the user explicitly wants to update "only the conflicts" or "only the Gantt". The default is `app_rebuild` — it costs barely more and delivers both artifacts consistently.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "No _app.yaml manifest found" | Folder is not an app folder | Create `_app.yaml` first (see `manual_read('doc-kind-application')`) |
| "Unknown application type 'foo'" | `$meta.app` points to an app type without a registered service bean | Typo in the manifest? Currently supported app types are listed in the error message |
| "Folder is a 'kanban' app, expected 'calendar'" | The `_app.yaml` declares a different app type than the calendar tools expect | Check tool choice; use the dedicated tools for other apps |

## Anti-patterns

- **Don't call after every small edit.** The tool is cheap but not free. Once at the end of an edit session is enough.
- **Hand-edits to generated artifacts** (`_gantt.md`, `_conflicts.yaml`) disappear on the next rebuild. Edit the sources.
- **Don't call in the wrong app folder.** If `app_rebuild` is called with a folder that has no `_app.yaml`, it throws — verify beforehand with `doc_read` or similar.

## Related

- `manual_read('app-calendar')` — the calendar-specific workflow including folder layout and `_app.yaml` schema
- `manual_read('doc-kind-application')` — the app pattern in general, in case the LLM wants to edit the manifest directly
- `manual_read('calendar-aggregate')` — for read queries without a write operation
