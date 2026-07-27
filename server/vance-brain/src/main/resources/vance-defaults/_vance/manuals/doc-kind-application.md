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
    tagFilter: []                       # empty = all non-recurring events
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

Several application types are in production. The authoritative list is provided
at runtime by `VanceApplicationRegistry.knownAppNames()`; currently registered are:

| `app:` | Container for | Own manual |
|---|---|---|
| `calendar` | Calendar / appointment documents (Gantt, conflicts) | see above |
| `kanban` | Board with cards in columns | `manual_read('app-kanban')` |
| `wiki` | Interlinked Markdown pages | `manual_read('app-wiki')` |
| `workbook` | `kind: workpage` pages (block editor) | `manual_read('app-workbook')` |
| `canvasbook` | `kind: canvas` pages (2D surface) | `manual_read('app-canvasbook')` |
| `gtd` | Getting-Things-Done lists | `manual_read('app-gtd')` |
| `issues` | Issue tracker | `manual_read('app-issues')` |
| `journal` | Diary / log entries | `manual_read('app-journal')` |
| `slideshow` | Presentation decks | `manual_read('app-slideshow')` |
| `common-desktop` | Desktop container | — |

An unknown `app:` value is rejected by the backend; the error message lists the
live registered names from `knownAppNames()` — don't guess, when in doubt read
the per-app manual. Schema details per app are **not** here, but in the
respective addon manual.

## Multi-face apps (v2, planned)

A folder can later carry multiple app faces — e.g. be a calendar AND a kanban board at the same time. The manifest already supports this structurally (each app type has its own sub-block):

```yaml
$meta:
  kind: application
  app:  calendar          # primary face

calendar:
  lanes: ...

kanban:                   # secondary face (v2)
  columns: [todo, doing, done]
```

v1 ignores secondary blocks — they are passed through round-trip-stable as `extra`.

## Anti-patterns

- **Don't write the manifest with the wrong `kind`.** `kind: calendar-suite`, `kind: app`, `kind: project` are all not recognized. It must be exactly `kind: application`.
- **Don't omit `$meta.app`.** Without the discriminator the app ends up in an undefined state — tools throw "App folder has no $meta.app value".
- **Don't move generated paths around.** `gantt.outputPath` and `conflicts.outputPath` *can* be adjusted, but `_gantt.md` / `_conflicts.yaml` are the established convention. Only change them if the user explicitly insists.
- **Don't hand-build the manifest from scratch** when `doc_write(kind="application", …)` has a suitable stub template. In the Web UI there's one under "New Document → kind: application".

## Related

- `manual_read('app-calendar')` — the app workflow with all tools
- `manual_read('app-rebuild')` — generic rebuild tool
- `manual_read('doc-kind-calendar')` — the format of the individual calendar files in the lane subfolders
- Spec: `specification/doc-kind-application.md`
