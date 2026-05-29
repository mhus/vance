---
triggers: app_rebuild, regenerate gantt, refresh gantt, update gantt, regenerate conflicts, regenerate plan, neu generieren, projektplan aktualisieren, refresh project, rebuild app, kalender neu bauen, gantt aktualisieren, plan refresh
summary: Generic refresh tool for any Vance application folder. Reads _app.yaml, dispatches to the right per-app service, regenerates all derived artifacts (Gantt + conflicts for app:calendar; later boards for app:kanban, indexes for app:wiki, etc.).
---
# Tool — `app_rebuild`

Regenerate every derived artifact in a Vance application folder. The tool is **generic** — it reads `_app.yaml`, looks at `$meta.app`, and routes to the matching Java service (`CalendarsApplication` for `app: calendar`, future `KanbanApplication` for `app: kanban`, …).

## When to use this

Whenever the user has changed something inside a calendar / kanban / wiki app and wants the views updated:

- "Update den Gantt-Chart"
- "Regenerier die Konflikt-Liste"
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
✓ Projektplan aktualisiert:

📊 [Gantt-Chart](vance:/projects/website/calendars/_gantt.md) — 12 Events in 3 Lanes
⚠ [Konflikt-Übersicht](vance:/projects/website/calendars/_conflicts.yaml) — 2 Konflikte
```

## Use this — not the granular tools — by default

For `app: calendar` there are also:

- `calendar_conflicts(folder)` — refresh nur `_conflicts.yaml`
- `gantt_from_calendars(folder)` — refresh nur `_gantt.md`

Diese gibt es als Spezial-Werkzeuge wenn der User explizit "nur die Konflikte" oder "nur den Gantt" updaten will. Standard ist `app_rebuild` — kostet kaum mehr und liefert beide Artefakte konsistent.

## Failure modes

| Symptom | Likely cause | Recovery |
|---|---|---|
| "No _app.yaml manifest found" | Folder ist kein App-Folder | Erst `_app.yaml` anlegen (siehe `manual_read('doc-kind-application')`) |
| "Unknown application type 'foo'" | `$meta.app` zeigt auf einen App-Type ohne registriertes Service-Bean | Tippfehler im Manifest? Aktuell unterstützte App-Types stehen in der Fehlermeldung |
| "Folder is a 'kanban' app, expected 'calendar'" | Im `_app.yaml` steht ein anderer App-Type als die Calendar-Tools erwarten | Tool-Wahl prüfen; für andere Apps eigene Tools nutzen |

## Anti-patterns

- **Nicht nach jeder kleinen Edit aufrufen.** Tool ist günstig aber nicht kostenlos. Einmal am Ende einer Edit-Session reicht.
- **Hand-Edits an generated artifacts** (`_gantt.md`, `_conflicts.yaml`) verschwinden beim nächsten Rebuild. Edit die Sources.
- **Nicht im falschen App-Folder aufrufen.** Wenn `app_rebuild` mit einem Folder ohne `_app.yaml` aufgerufen wird, throws — vorher mit `doc_read` oder ähnlich verifizieren.

## Related

- `manual_read('app-calendar')` — der Calendar-spezifische Workflow inkl. Folder-Layout und `_app.yaml`-Schema
- `manual_read('doc-kind-application')` — das App-Pattern generell, falls der LLM den Manifest direkt editieren will
- `manual_read('calendar-aggregate')` — für Read-Queries ohne Schreib-Operation
