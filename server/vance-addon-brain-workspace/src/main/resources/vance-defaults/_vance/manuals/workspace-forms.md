---
triggers: form, data entry form, editable form, record form, fillable form, collect data, data table with form, recompute on save, reactive data, form that runs a script, generate chart from data, dropdown form, survey, intake form, dataset with computed output, editable text field, text input, inline editable text, single text value, note field, textarea on page, editable note
summary: How to build an editable, typed FORM over a data document, optionally with an onSave script that recomputes derived files (chart, summary, …) when the user saves. The form doc is a `kind: records` document whose `$meta.form` carries the typed schema and `$meta.onSave` the recompute hook. Use this when the user wants to enter structured data and have something computed/visualised from it.
---
# Reactive Forms in a Workspace

A **form** is a `kind: records` document that carries its own typed schema in
`$meta.form` and (optionally) a recompute script in `$meta.onSave`. The user
edits it as a form; on Save the data is written to `items` and the onSave
script recomputes derived files. The same document is also a normal records
table when embedded read-only.

Use this when the user wants to **enter structured data** (people, expenses,
tasks, survey answers …) and optionally **compute/visualise** something from it
(a chart, a summary, an aggregate).

## 1. The form document

Create a `.yaml` document (e.g. `team/people.yaml`) with this shape:

```yaml
$meta:
  kind: records
  form:
    single: false              # false = card list (many records); true = one record
    fields:
      - { name: name,   type: string,  label: { en: Name }, required: true }
      - { name: email,  type: string,  label: { en: Email } }
      - { name: role,   type: select,  label: { en: Role },
          choices: [ { value: admin }, { value: user } ] }
  onSave:
    runScript: by-role.js        # optional, relative to this folder; must be .js
    session: false               # true only if the script needs vance.llm / session tools
schema: [name, email, role]      # native table columns — keep == field names
items: []                        # the data rows (start empty)
```

Field `type`: `string` | `textarea` | `integer` | `boolean` | `select` |
`multi_select`. Each field has `name` (the data key + column), `label`
(i18n map), `required`, and `choices` for the select types.

- **`single: false`** → a card list; the user adds/removes records.
- **`single: true`** → a single form (one record).

Keep the document as **YAML** (nested `$meta` only round-trips in YAML/JSON).
Values are stored as **strings**.

## 2. Embed it in a WorkPage

Add a `vance-form` block pointing at the document so the user can edit it:

````
```vance-form
config: vance:/team/people.yaml?kind=records
```
````

To also show a computed result, embed the output document read-only:

````
```vance-embed
uri: vance:/team/_by-role.chart.yaml?kind=chart
```
````

The embedded output auto-refreshes after each Save (the onSave script writes
it; the live-push updates the embed).

## 3. The onSave script (`runScript`)

A `.js` document that runs **synchronously on Save**, server-side. It reads the
fresh data and writes derived files via `vance.documents.*`. A failure surfaces
to the user as an error; the data stays saved.

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
- `vance.llm.call(recipe, prompt)` / `.callForJson(...)` — single-shot LLM
  (needs `onSave.session: true` is **not** required for LLM, but set it if a
  tool you call is session-bound). Recipe must be `internal: true`.
- `vance.settings.get(key)` — read settings.
- `vance.log.info(...)` — logging.

Scope is pinned to the current tenant/project — the script cannot reach other
projects. Only `.js` is supported; timeout 30 s.

## 4. Design vs. Work mode (UI hint for the user)

In the workspace the user toggles **Design** (🛠) vs **Work** (✎) mode. In
Design mode they edit fields (field builder) and open **⚙ Settings** to set
`single`, the onSave script, the session flag and the title. In Work mode they
enter data and Save. You (the model) normally set all of this directly in the
YAML when you create the document — no UI step needed.

## 4b. `/input` — a single editable text bound to a file

For **one editable text value** (a note, a description, a single field) use the
simpler `vance-input` block instead of a full form. It binds to a plain text
document; Save writes the **whole** file content.

````
```vance-input
config: vance:/notes/intro.md?kind=text
multiline: true
```
````

- `multiline: false` → a single-line input; `true` → a textarea.
- Work mode: the user edits the text and Saves. Design mode: a single/multi
  toggle. The value is the entire file — no schema, no `items`, no onSave script.
- If you embed the same file elsewhere with `vance-embed`, it refreshes live
  after a save.

Use `/input` for a lone text value; use `/form` (`$meta.form`) when you need
typed fields, multiple records, or an onSave recompute.

## 5. Don't

- Don't add a document-change hook to recompute — the trigger is the Save
  button only.
- Don't hand-maintain `schema`; mirror it from `$meta.form.fields` names.
- Don't use Markdown for the form document — use `.yaml`.
- Don't expect typed values back — everything in `items` is a string.

Related: `manual_read('app-workspace')`, `manual_read('workpage-blocks')`,
`manual_read('doc-kind-records')`.
