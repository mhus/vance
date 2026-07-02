---
triggers: form, data entry form, editable form, record form, fillable form, collect data, data table with form, recompute on save, reactive data, form that runs a script, generate chart from data, dropdown form, survey, intake form, dataset with computed output, editable text field, text input, inline editable text, single text value, note field, textarea on page, editable note, button, run button, action button, run script button, trigger script, click to run
summary: How to build an editable, typed FORM over a data document, optionally with a save-script that recomputes derived files (chart, summary, …) when the user saves. The form definition (fields + single) and the recompute script live in the `vance-form` FENCE (block), NOT in the data file — the `kind: records` document holds only `schema` + `items`. Use this when the user wants to enter structured data and have something computed/visualised from it.
---
# Reactive Forms in a Workspace

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
`vance-form` **fence**, not in the record file. Also in the fence: `config`
(the data doc) and optional `saveScript`.

````
```vance-form
config: vance:/team/people.yaml?kind=records
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

```js
// by-role.js — count people per role, write a bar chart next to the data
const doc = vance.documents.read('team/people.yaml');
// doc is YAML text; parse the items (simple split or JSON if you wrote JSON)
const rows = (vance.documents.exists('team/people.yaml'))
  ? (JSON.parse(vance.documents.read('team/people.json') || '{"items":[]}').items || [])
  : [];
const counts = {};
for (const r of rows) counts[r.role] = (counts[r.role] || 0) + 1;

vance.documents.write('team/_by-role.chart.yaml', [
  '$meta:', '  kind: chart', 'type: bar',
  'categories: [' + Object.keys(counts).join(', ') + ']',
  'series:', '  - name: Headcount',
  '    data: [' + Object.values(counts).join(', ') + ']',
].join('\n'));
```

**What the script can do** (see `manual_read('python')` analog / Script
Document API):

- `vance.documents.read(path)` / `.write(path, text)` / `.list(prefix)` /
  `.exists(path)` / `.delete(path)` / `.meta(path)` — project documents.
- `vance.settings.get(key)` — read settings.
- `vance.log.info(...)` — logging.

Scope is pinned to the current tenant/project — the script cannot reach other
projects. The run has **no session**, so session-bound APIs (LLM calls etc.)
are unavailable — a saveScript is a pure data transformation over
`vance.documents.*`. Only `.js` is supported; timeout 30 s.

## 4. Design vs. Work mode (UI hint for the user)

In the workspace the user toggles **Design** (🛠) vs **Work** (✎) mode. In
Design mode the field builder (fields + single toggle) edits the block's fence
`form:`; "Apply fields" writes it back into the fence. In Work mode they enter
data and Save. You (the model) normally author the whole `vance-form` fence
(`config` + `saveScript` + `form:`) directly — no UI step needed.

## 4b. `/input` — a single editable text bound to a file

For **one editable text value** (a note, a description, a single field) use the
simpler `vance-input` block instead of a full form. It binds to a plain text
document; Save writes the **whole** file content.

````
```vance-input
config: vance:/notes/intro.md?kind=text
multiline: true
saveScript: vance:update.js
```
````

- `multiline: false` → a single-line input; `true` → an auto-growing textarea
  (no scrollbar). The user picks the bound file (existing text doc or a new one)
  via the `/input` picker when inserting.
- `saveScript` (optional): a `.js` document — same fence key and semantics as the
  form (§3), runs on Save with no session. Omit for no recompute.
- Work mode: the user edits the text and Saves. Design mode: a single/multi
  toggle plus a `saveScript` field. The value is the file **body** — a
  front-matter header, if present, is preserved but not edited.
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

## 5. Don't

- Don't add a document-change hook to recompute — the trigger is the Save
  button only.
- Don't hand-maintain the data doc's `schema` — Save syncs it from the fence
  form's field names.
- Don't put the `form:` definition in the data file — it belongs in the fence.
- Don't use Markdown for the data document — use `.yaml`/`.json`.
- Don't expect typed values back — everything in `items` is a string.

Related: `manual_read('app-workspace')`, `manual_read('workpage-blocks')`,
`manual_read('doc-kind-records')`.
