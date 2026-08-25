---
triggers: timeline, Timeline, Zeitleiste, Zeitstrahl, Zeitachse, Chronologie, chronology, Erdzeitalter, geological, Jura, Kreidezeit, epochs, Epochen, Ären, eras, Tathergang, Ablauf rekonstruieren, reconstruction, Ereigniskette, sequence of events, Projektphasen, phases, Lebenslauf, biography, Geschichte von, history of, Meilensteine über Jahre
summary: Vance Document Kind for periods and points on a declared axis — deep time in millions of years, a crime reconstruction in minutes, project phases. Parallel lanes, nested periods, explicit uncertainty. Read-only; YAML/JSON only.
---
# Document Kind — `timeline`

Use this kind when the user wants **spans and moments read against one axis**: geological or historical eras, the reconstruction of a sequence of events, project phases, a life story, the stages of a process, a story's chronology.

The one thing that makes it a timeline and not a calendar: **the document declares its own axis.** Either a bare number line with a unit you choose, or ISO-8601 date-times. That is why deep time and minute-resolution reconstruction both fit here and neither fits a calendar.

## When to pick `timeline` over other kinds

| Picking … | When |
|---|---|
| `timeline` | Periods and points on a declared axis. Parallel strands. Nesting (era ⊃ period ⊃ epoch). Anything outside the Gregorian calendar's reach — millions of years, "hours after the alarm", BCE. |
| `calendar` | Appointments a person keeps: meetings, deadlines, recurring standups, vacation. Anything you would push to Google Calendar. |
| `diagram` (mermaid) | A *drawing* — flowchart, sequence, state machine. Mermaid's own `timeline` type has no proportional axis (equal spacing regardless of gaps) and its `gantt` cannot express negative years; neither is a substitute here. |
| `records` | Tabular data without a primary time axis. |
| `tree` / `mindmap` | Hierarchy without a time axis. |

Deciding question: **does the distance between two items mean something?** Yes → `timeline`. No → `diagram` or `list`.

## Vance does not do project management

A timeline draws time. It has **no** task dependencies, **no** resource assignment, **no** percent-complete, **no** critical path. If the user wants a Gantt chart in that sense, say so and point at MS Project / Linear / GitHub Projects. Do **not** approximate dependencies with `parent` — `parent` is containment (the Oberjura is *inside* the Jura), not "happens after".

## Format — YAML/JSON only

Markdown is **not supported**. Create with `mimeType: application/yaml` (preferred) or `application/json`.

## Schema

```yaml
$meta:
  kind: timeline
title: <optional heading above the ruler>
axis:
  mode: numeric | datetime     # required in practice; defaults to numeric
  unit: <string>               # numeric only — "Ma", "ka", "yr BP", "min"
  direction: forward | ago     # numeric only; default forward
  from: <position>             # optional visible window
  to:   <position>
  label: <optional caption under the ruler>
lanes:                         # optional, in render order
  - id: <lane-id>
    title: <display label>
    color: <palette|css>
entries:
  - id: <stable-id>            # optional; auto-filled, needed only as a parent target
    title: <required>
    from: <required position>
    to: <position>             # present = PERIOD (bar), absent = POINT (marker)
    fromEarliest: <position>   # uncertainty on the start
    fromLatest:   <position>
    toEarliest:   <position>   # uncertainty on the end
    toLatest:     <position>
    lane: <lane-id>
    parent: <entry-id>         # containment: era > period > epoch
    color: <palette|css>
    tags: [<tag>, ...]
    notes: <multiline>
```

Hard requirements per entry: `title` + `from`. Entries missing either are **silently dropped** on parse — `kind_validate` names them.

Aliases read on input: `at` and `start` mean `from`; `end` and `until` mean `to`. They are normalised to the canonical names on save, so use the canonical ones.

## The axis is the decision — get it right first

**One axis per document.** A document mixing `201.4` and `2026-03-04T21:40` has no defensible ordering. Two subjects on different scales are two documents.

### `mode: numeric`

Positions are bare numbers. The unit lives in `axis.unit` and **never** in the value — `from: 201.4 Ma` is unreadable and the entry will not be drawn.

`direction: ago` flips the reading: **a larger number is earlier**. Use it for every "… ago" scale (geology, archaeology, "days before the incident"). Consequence you must get right: a period then runs **from the larger to the smaller number**.

```yaml
axis: { mode: numeric, unit: Ma, direction: ago }
entries:
  - title: Jura
    from: 201.4      # earlier — the LARGER number
    to: 143.1        # later
```

Writing `from: 143.1, to: 201.4` there means the Jurassic ends before it begins. That is the single most common mistake with this kind.

### `mode: datetime`

Positions are ISO-8601: `2026-03-04T21:40`, `2026-03-04T21:40:00+01:00`, `2026-03-04`, and — deliberately — a bare year `1969` or a BCE year `-0044-03-15`. A bare year means the start of that year, so `1969` sorts before `1969-07-20`.

A value without an offset is read as written; a reader in another time zone sees the clock the author typed, not a shifted one. Do not "helpfully" convert times to UTC.

## Periods and points are the same thing

There is **no** separate event type. `to` present → a bar. `to` absent → a marker. Do not create two documents, two lanes, or two arrays for "periods" and "events" — put them in one `entries` list.

## Lanes — the axis a calendar does not have

Lanes are the parallel strands read against one clock. This is usually what makes a timeline worth making:

- a reconstruction: `taeter`, `opfer`, `zeuge`, `polizei`
- a geological chart: `stratigraphie`, `klima`, `fauna`
- a project: `design`, `backend`, `ops`

Declare lanes when the order matters, or when a lane must be visible **while empty** — "no record of the witness that night" is a statement, and an undeclared empty lane cannot say it. Entries naming an undeclared lane still render, appended after the declared ones.

Lanes are display order only. No scope, no permissions, nothing cascades along them.

## Nesting via `parent`

An era containing periods containing epochs is a **flat list plus `parent`**, never nested objects. The renderer draws each depth as its own row band, so the hierarchy reads as indentation. A `parent` pointing at a missing id renders at the top level; a circular chain is an error.

## Uncertainty is content, not decoration

If the start or end is not exactly known, **say so in the bounds**. Writing "gegen 22 Uhr" into `notes` and putting a precise `from` makes the drawing assert a precision nobody claimed — the reader sees a hard edge where there is none.

```yaml
# "Last seen between 21:40 and 22:05"
- title: Opfer zuletzt gesehen
  from: '2026-03-04T21:40'
  fromLatest: '2026-03-04T22:05'

# "201.4 ± 0.2 Ma" on an ago axis — earlier is the larger number
- title: Trias-Jura-Grenze
  from: 201.4
  fromEarliest: 201.6
  fromLatest: 201.2

# A period that started at a known time and ended sometime in a window
- title: Brand
  from: '2026-03-04T22:10'
  to:   '2026-03-04T23:00'
  toLatest: '2026-03-04T23:30'
```

The renderer draws the certain part solid and the uncertain edges faded. A window must **contain** the position it qualifies; `toEarliest` / `toLatest` need a `to`.

## Creating a timeline — `timeline_create` is the canonical tool

```
timeline_create(
  title="Tathergang 4./5. März",
  axis={ "mode": "datetime", "label": "Nacht vom 4. auf den 5. März" },
  lanes=[
    { "id": "opfer",  "title": "Opfer" },
    { "id": "taeter", "title": "Verdächtiger", "color": "red" },
    { "id": "polizei","title": "Polizei", "color": "blue" }
  ],
  entries=[
    { "title": "Opfer zuletzt gesehen", "from": "2026-03-04T21:40",
      "fromLatest": "2026-03-04T22:05", "lane": "opfer",
      "notes": "Aussage Nachbarin, Zeitangabe unscharf" },
    { "title": "Handy in Funkzelle Nord", "from": "2026-03-04T22:02",
      "lane": "taeter", "notes": "Funkzellenabfrage" },
    { "title": "Brand", "from": "2026-03-04T22:10", "to": "2026-03-04T23:00",
      "toLatest": "2026-03-04T23:30", "lane": "opfer", "color": "orange" },
    { "title": "Notruf", "from": "2026-03-04T22:31", "lane": "polizei" },
    { "title": "Eintreffen Streife", "from": "2026-03-04T22:39",
      "to": "2026-03-05T02:15", "lane": "polizei" }
  ],
  outputPath="timelines/tathergang.yaml"
)
```

Returns `{ path, entryCount, periodCount, laneCount, vanceUri, markdownLink }`. Embed `markdownLink` in your answer so the user opens it with one click.

`axis.mode` is required and **not** inferred: `201.4` is a plausible year and a plausible "millions of years ago", and a wrong guess draws the whole timeline mirror-imaged with no error anywhere.

The tool rejects a position its axis cannot read instead of writing it, so a successful call means every entry is drawn.

If you need `extra` fields the typed tool does not expose, `doc_create_kind(kind="timeline", mimeType="application/yaml", body=…)` still works.

## Editing — full-body rewrites in v1

There are no granular `timeline_add_entry` / `timeline_remove_entry` tools. Read with `doc_read`, change the YAML, write it back with `doc_edit`. Run `kind_validate` afterwards — it reports dropped entries, unreadable positions, reversed periods, broken `parent` chains and impossible uncertainty windows.

## Anti-patterns

- **Don't put the unit in the position.** `from: "201.4 Ma"` is unreadable; `axis.unit: Ma` + `from: 201.4`.
- **Don't write an `ago` period forwards.** `from` is the larger number.
- **Don't mix numeric and datetime positions** in one document.
- **Don't hide uncertainty in `notes`.** Use the four bounds.
- **Don't model dependencies.** No `dependsOn`, and `parent` is containment, not sequence.
- **Don't use `parent` to fake lanes** or lanes to fake nesting. Lanes are *who/what*, `parent` is *inside*.
- **Don't reach for `calendar`** because the entries happen to have dates. If the user thinks in eras, phases or a reconstruction, it is a timeline.
- **Don't emit a Mermaid `gantt` or `timeline`** for this. It cannot do proportional deep time, lanes, nesting or uncertainty.
- **Don't use unquoted ISO dates in YAML.** Quote them: `from: "2026-03-04"`. The codec coerces, other tools may not.
- **Don't create one document per lane.** Lanes exist so one document holds them.
