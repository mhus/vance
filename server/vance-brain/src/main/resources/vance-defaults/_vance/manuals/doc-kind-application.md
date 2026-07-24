---
triggers: _app.yaml, app manifest, kind application, vance application, application folder, app bundle, application config, _app file, app-yaml editieren, manifest editieren, lane hinzufügen, lane entfernen, app-konfiguration
summary: Schema reference for _app.yaml — the manifest at the root of a Vance "application folder". Use this when you need to edit the manifest directly (add a lane, change colors, tweak config) without going through app-specific tools.
---
# Document Kind — `application`

The `_app.yaml` manifest turns a folder into a Vance application instance — a calendar suite, a kanban board, a wiki, etc. It's a plain YAML/JSON config file, the same parser as any other document.

## When to read / edit this

- **Adding / renaming a lane** in a calendar app
- **Changing a Gantt setting** (`includeRecurring`, `tagFilter`, …)
- **Adjusting the conflict-filter** (`ignoreWithinTags`, …)
- **Renaming the app** (`title`, `description`)
- **Switching app type** (rare — usually you create a new app instead)
- **Inspecting** what an app folder is configured to do

For the **standard user flow** (create-app + populate + rebuild) use the dedicated tools — `calendar_create`, `app_rebuild`, etc. — *not* this manual. Only edit the manifest directly when you need to change something the tools don't expose.

## Mandatory shape

```yaml
$meta:
  kind: application       # required
  app:  calendar          # required — picks the app type
```

The two `$meta` fields are mirrored to the database (`DocumentDocument.kind` for `kind`, `headers.app` for `app`), so queries can find apps without scanning bodies.

## Calendar-App schema (`app: calendar`)

Full schema lives under `config.calendar` (i.e. a top-level `calendar:` block in the YAML, nested under `$meta`):

```yaml
$meta:
  kind: application
  app: calendar

title: "Sprint Q3"
description: "Design + Backend + Frontend"

calendar:
  window:
    from:  "2026-06-01"
    until: "2026-09-30"

  lanes:
    design:   { title: "Design",   color: blue,   order: 1 }
    backend:  { title: "Backend",  color: green,  order: 2 }
    frontend: { title: "Frontend", color: purple, order: 3 }

  gantt:
    outputPath: "_gantt.md"
    includeRecurring: false
    tagFilter: []                       # leer = alle non-recurring Events
    criticalTags: [milestone, critical] # → :crit im Gantt
    doneTags:     [done, erledigt]      # → :done
    sectionOrder: [design, backend, frontend]

  conflicts:
    outputPath: "_conflicts.yaml"
    ignoreWithinTags: [private]
    ignoreAllDayOverlapsBetweenLanes: false
```

Every field except `$meta.kind` and `$meta.app` is **optional**. Auto-defaults kick in when absent — see `manual_read('app-calendar')` for the full default-and-fallback list.

## Editing the manifest

Read first, edit, write back:

```
doc_read(path="projects/website/calendars/_app.yaml")
# → existing YAML body

doc_edit(
  path="projects/website/calendars/_app.yaml",
  content="<full new YAML body>"
)

# Re-generate artifacts so the change shows up:
app_rebuild(folder="projects/website/calendars")
```

**Always `app_rebuild` after editing the manifest** — the new lane / color / filter only shows up in `_gantt.md` and `_conflicts.yaml` after rebuild.

## Available app types

Mehrere Application-Typen sind produktiv. Die verbindliche Liste liefert zur
Laufzeit `VanceApplicationRegistry.knownAppNames()`; aktuell registriert sind:

| `app:` | Container für | Eigenes Manual |
|---|---|---|
| `calendar` | Kalender-/Termin-Dokumente (Gantt, Konflikte) | siehe oben |
| `kanban` | Board mit Cards in Spalten | `manual_read('app-kanban')` |
| `wiki` | vernetzte Markdown-Pages | `manual_read('app-wiki')` |
| `workbook` | `kind: workpage`-Seiten (Block-Editor) | `manual_read('app-workbook')` |
| `canvasbook` | `kind: canvas`-Seiten (2D-Fläche) | `manual_read('app-canvasbook')` |
| `gtd` | Getting-Things-Done-Listen | `manual_read('app-gtd')` |
| `issues` | Issue-Tracker | `manual_read('app-issues')` |
| `journal` | Tagebuch-/Log-Einträge | `manual_read('app-journal')` |
| `slideshow` | Präsentations-Decks | `manual_read('app-slideshow')` |
| `common-desktop` | Desktop-Container | — |

Ein unbekannter `app:`-Wert wird vom Backend abgelehnt; die Fehlermeldung führt
die live registrierten Namen aus `knownAppNames()` — nicht raten, im Zweifel das
per-App-Manual lesen. Schema-Details pro App stehen **nicht** hier, sondern im
jeweiligen Addon-Manual.

## Multi-face apps (v2, geplant)

Ein Folder kann später mehrere App-Faces tragen — z.B. gleichzeitig ein Calendar UND ein Kanban-Board sein. Das Manifest unterstützt das schon strukturell (jeder App-Type hat seinen eigenen Sub-Block):

```yaml
$meta:
  kind: application
  app:  calendar          # primary face

calendar:
  lanes: ...

kanban:                   # secondary face (v2)
  columns: [todo, doing, done]
```

v1 ignoriert sekundäre Blocks — sie werden round-trip-stabil als `extra` durchgereicht.

## Anti-patterns

- **Don't write the manifest with the wrong `kind`.** `kind: calendar-suite`, `kind: app`, `kind: project` werden alle nicht erkannt. Es muss exakt `kind: application` sein.
- **Don't omit `$meta.app`.** Ohne den Discriminator landet die App in einem undefined state — tools werfen "App folder has no $meta.app value".
- **Don't move generated paths around.** `gantt.outputPath` und `conflicts.outputPath` *können* angepasst werden, aber `_gantt.md` / `_conflicts.yaml` sind die etablierte Konvention. Anpassen nur wenn der User explizit darauf besteht.
- **Don't hand-build the manifest from scratch** wenn `doc_create(kind="application", …)` ein passendes Stub-Template hat. Im Web-UI gibt's eines unter "New Document → kind: application".

## Related

- `manual_read('app-calendar')` — der App-Workflow mit allen Tools
- `manual_read('app-rebuild')` — Generic Rebuild-Tool
- `manual_read('doc-kind-calendar')` — das Format der einzelnen Calendar-Files in den Lane-Unterordnern
- Spec: `specification/doc-kind-application.md`
