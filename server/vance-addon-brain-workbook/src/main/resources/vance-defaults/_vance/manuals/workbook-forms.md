---
triggers: form, data entry form, editable form, record form, fillable form, collect data, data table with form, recompute on save, reactive data, form that runs a script, generate chart from data, dropdown form, survey, intake form, dataset with computed output, editable text field, text input, inline editable text, single text value, note field, textarea on page, editable note, button, run button, action button, run script button, trigger script, click to run, vance-form, vance-form fence, vance-form block, form fence, vance-input, vance-input fence, vance-button, vance-button fence, saveScript fence, workbook form, quiz, test, exam, exam prep, exam preparation, multiple choice, single choice, checkbox question, questionnaire, self test, grade answers, resolve answers, vance-field, field block, vance-field fence, form-resolve, form-reset
summary: How to build an editable, typed FORM over a data document, optionally with a save-script that recomputes derived files (chart, summary, …) when the user saves. The form definition (fields + single) and the recompute script live in the `vance-form` FENCE (block), NOT in the data file — the `kind: records` document holds only `schema` + `items`. Use this when the user wants to enter structured data and have something computed/visualised from it.
---
# Reactive Forms in a Workbook

A **form** is a `vance-form` block that renders a typed form over a
`kind: records` data document. The **form definition** (fields + single) and
the recompute **saveScript** live in the block's **fence** — block-specific, so
the same data doc can back different forms. The data file holds only `schema` +
`items`. On Save the data is written to `items` and, if a `saveScript` is set,
that script recomputes derived files. The data doc is also a normal records
table when embedded read-only.

Use this when the user wants to **enter structured data** (people, expenses,
tasks, survey answers …) and optionally **compute/visualise** something from it
(a chart, a summary, an aggregate).

## 1. The data document (record file)

Create a plain `kind: records` document — it holds **only the data**, no form
definition:

```yaml
$meta:
  kind: records
schema: [name, email, role]      # native table columns (kept in sync by save)
items: []                        # the data rows (start empty)
```

Keep it **YAML/JSON**. Values are stored as **strings**.

## 2. Embed it as a form (the form def lives in the fence)

The **form definition** (fields + single) is block-specific, so it lives in the
`vance-form` **fence**, not in the record file. Also in the fence: `data`
(the data doc) and optional `saveScript`.

````
```vance-form
data: vance:/team/people.yaml?kind=records
saveScript: vance:by-role.js
form:
  single: false            # false = card list (many records); true = one record
  fields:
    - name: name
      type: string
      label: Name
      required: true
    - name: role
      type: select
      label: Role
      choices:
        - { value: admin }
        - { value: user }
```
````

- Field `type`: `string` | `textarea` | `integer` | `boolean` | `select` |
  `multi_select`. Each field has `name` (data key + column), `label` (a bare
  string is fine — coerced to `{en: …}`), `required`, and `choices` for selects.
- `saveScript` is a `vance:` URI/path: bare name resolves next to the data file;
  `vance:/abs/path.js` is project-absolute. Omit for no recompute.
- `session: true` (optional): run the saveScript inside a per-form system
  session so session-bound APIs (LLM calls etc.) work. Default omitted =
  sessionless (pure data transform). Only set it if the script needs it.

To show a computed result, embed the output document read-only:

````
```vance-embed
uri: vance:/team/_by-role.chart.yaml?kind=chart
```
````

The embedded output auto-refreshes after each Save (the saveScript writes it;
the live-push updates the embed).

## 3. The save script (fence `saveScript`)

The `.js` document named by the fence's `saveScript` runs **synchronously on
Save**, server-side. It reads the fresh data and writes derived files via
`vance.documents.*`. A failure surfaces to the user as an error; the data stays
saved.

> **The saveScript is a project DOCUMENT — create it with `doc_write`,
> NEVER `work_file_write`.** Everything in a workbook (the data
> doc, the workpage, and this `.js` saveScript) is a project document resolved
> by path. `work_file_*` writes to the Brain's WORK sandbox (a separate
> `WorkspaceRootService` RootDir) — those files are invisible to the workbook,
> and `work_file_write` on an app path fails with "Unknown RootDir". Same for a
> `/button` script.

**Paths are relative to the script's own folder** (its "current path"). A
saveScript at `apps/grades/calc.js` reading `data/noten.records.json` resolves
to `apps/grades/data/noten.records.json` — use the same relative paths the
workbook's fence keys use. A **leading `/` is project-root absolute**
(`/shared/x.yaml`). This is the documents system (documents + paths), distinct
from the WORK sandbox (files + directories).

```js
// calc.js in apps/grades/ — relative paths resolve against apps/grades/
const src = JSON.parse(vance.documents.read('data/noten.records.json'));
const notes = (src.items ?? []).map(r => parseFloat(r.note)).filter(n => !isNaN(n));
const out = notes.length
  ? `Notendurchschnitt: **${(notes.reduce((a, b) => a + b, 0) / notes.length).toFixed(2)}**`
  : 'Noch keine Noten eingetragen.';
vance.documents.write('data/durchschnitt.md', out);
```

**What the script can do** (see `manual_read('python')` analog / Script
Document API):

- `vance.documents.read(path)` / `.write(path, text)` / `.list(prefix)` /
  `.exists(path)` / `.delete(path)` / `.meta(path)` — project documents.
- `vance.settings.get(key)` — read settings.
- `vance.log.info(...)` — logging.
- `vance.llm.call(...)` / session-bound tools — **only** when the fence sets
  `session: true` (see above); otherwise the run is sessionless.

Scope is pinned to the current tenant/project — the script cannot reach other
projects. Only `.js` is supported; timeout 30 s.

## 4. Design vs. Work mode (UI hint for the user)

In the workbook the user toggles **Design** (🛠) vs **Work** (✎) mode. In
Design mode the field builder (fields + single toggle + session checkbox) edits
the block's fence; "Apply fields" writes the form def back into the fence. In
Work mode they enter data and Save. You (the model) normally author the whole
`vance-form` fence (`data` + `saveScript` + `session` + `form:`) directly —
no UI step needed.

## 4b. `/input` — a single editable text bound to a file

For **one editable text value** (a note, a description, a single field) use the
simpler `vance-input` block instead of a full form. It binds to a plain text
document and treats it **verbatim** — the whole file content is the value, no
header split.

````
```vance-input
data: vance:/notes/intro.md?kind=text
multiline: true
saveScript: vance:update.js
session: true
```
````

- `multiline: false` → a single-line input; `true` → an auto-growing textarea
  (no scrollbar). The user picks the bound file (existing text doc or a new one)
  via the `/input` picker when inserting.
- `saveScript` (optional): a `.js` document — same fence key and semantics as the
  form (§3), runs on Save. Omit for no recompute.
- `session: true` (optional): run the saveScript inside a per-input system
  session (for LLM / session-bound tools). Default omitted = sessionless.
- Work mode: the user edits the text and Saves. Design mode: a single/multi
  toggle plus a `saveScript` field. The value is the **whole file content** —
  a text file is plain, there is no header.
- If you embed the same file elsewhere with `vance-embed`, it refreshes live
  after a save.

Use `/input` for a lone text value; use `/form` (fence `form:`) when you need
typed fields or multiple records.

## 4c. `/button` — run a script on click

`vance-button` runs a project script when clicked. v1 supports `type: script`.

````
```vance-button
type: script
title: Recompute everything
script: vance:update_all.js
```
````

- `script`: a `.js` document — a bare name resolves **relative to the app
  folder**, `vance:/abs/path.js` is project-absolute. `title` is the label.
- Work mode: a clickable button; click runs the script server-side (synchronous,
  `vance.documents.*` on the tenant/project scope, 30 s). Errors show inline;
  any documents the script writes refresh embeds live.
- Design mode: title / script inputs.

Use `/button` for an explicit "run this now" action; use `/form` when the
script should run when the user **saves data**.
## 4d. Quiz pages — inline `field` blocks + form actions

For a **quiz, multiple-choice test or exam-prep page** do NOT use a records
form + saveScript + result embed. Use the `field` block: an inline form field
whose answer (`value`) lives in the page fence itself — one participant, no
data document, no script.

```json
{ "type": "field", "id": "q1", "fieldType": "choice",
  "question": "Welches Organell ist für die Zellatmung zuständig?",
  "options": ["Ribosom", "Mitochondrium", "Zellkern"], "solution": 1 }
```

- `id` required, unique per page; `fieldType`: `choice` (radio) | `multi`
  (checkboxes) | `dropdown` | `text` | `textarea`.
- `solution`: option index (`choice`/`dropdown`), index list (`multi`) or a
  reference string (`text`/`textarea`). Optional — a field without
  `solution` is a plain form element (checklist, survey).
- Never author `value`, `verdict` or `feedback`: `value` is the user's
  answer, `verdict`/`feedback` are grading output written by the action.
- Interleave prose, callouts and toggles between the fields — a quiz page
  is a normal workpage.

End the page with the grading buttons (no `script` key — the actions run
server-side over the canonical block model):

```json
{ "type": "button", "buttonType": "form-resolve", "title": "Auflösen" }
{ "type": "button", "buttonType": "form-reset", "title": "Zurücksetzen" }
```

- **`form-resolve`**: grades every field with a `solution` mechanically by
  index (unanswered = wrong; `text`/`textarea` are not graded yet), writes
  `verdict` (+ drops a stale `feedback`) into the fences, marks the fields
  green/red in the live editor and returns the score. No fields to check →
  no write.
- **`form-reset`**: removes `verdict`/`feedback` from all fields — the
  **answers are kept**, only the markings go.

Use a records `form` when the user collects **data rows** and wants something
**computed** from them; use `field` blocks when the user **answers
questions on the page** and wants them **checked**.

## 4e. Self-check before you finish

After building or editing a workbook, call
`workbook_validate('<folder-or-page>')` (a folder like `apps/grades` or a
single `…​.workpage.md`). It statically checks every fence: required keys
present, `data`/`uri`/`script`/`saveScript` references **exist** with the
right kind and `.js` extension, form field types valid, no legacy
`$meta.form`/`$meta.onSave` on the data doc; `vance-field`: unique `id`,
valid `type`, ≥2 options for the closed types, `solution`/`value` in bounds,
`verdict` in {correct, wrong}; `vance-button`: registered action `type`. It's **read-only** — safe to call anytime.
Fix reported `error`s before telling the user it's done. It does **not** check
runtime script logic.

## 5. Don't

- Don't add a document-change hook to recompute — the trigger is the Save
  button only.
- Don't hand-maintain the data doc's `schema` — Save syncs it from the fence
  form's field names.
- Don't put the `form:` definition in the data file — it belongs in the fence.
- Don't use Markdown for the data document — use `.yaml`/`.json`.
- Don't expect typed values back — everything in `items` is a string.
- Don't create the saveScript (or a `/button` script) with `work_file_write` —
  it's a project document (`doc_write`), see §3.
- Don't build a quiz with a records form + saveScript + result embed — use `field` blocks with `solution` and the `form-resolve` / `form-reset` buttons (§4d).

Related: `manual_read('app-workbook')`, `manual_read('workpage-blocks')`.
