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

## Future app types

```yaml
$meta:
  kind: application
  app:  kanban           # v2: Board mit Cards in Spalten-Unterordnern

kanban:
  columns: [todo, doing, done]
  ...
```

```yaml
$meta:
  kind: application
  app:  wiki             # v2: vernetzte Markdown-Pages

wiki:
  startPage: "index.md"
  ...
```

Heute existiert nur `app: calendar`. Andere Werte werden vom Backend abgelehnt:
```
"Unknown application type 'kanban'. Known: [calendar]"
```

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
- **Don't hand-build the manifest from scratch** wenn `doc_create_kind(kind="application", …)` ein passendes Stub-Template hat. Im Web-UI gibt's eines unter "New Document → kind: application".

## Related

- `manual_read('app-calendar')` — der App-Workflow mit allen Tools
- `manual_read('app-rebuild')` — Generic Rebuild-Tool
- `manual_read('doc-kind-calendar')` — das Format der einzelnen Calendar-Files in den Lane-Unterordnern
- Spec: `specification/doc-kind-application.md`
