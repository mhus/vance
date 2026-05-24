# Marvin Recipe — Shape

This manual describes what a Marvin Recipe is structurally.
Slartibartfast's GATHERING ingests it as engine-bundled evidence
so DECOMPOSING can tie subgoals to concrete claims even when no
project-specific kit is installed.

## Marvin's character — what makes it different

Marvin is **deep-think**, not process-driven. Unlike Vogon (which
runs a fixed phase plan), Marvin's PLAN-LLM decides the **shape
of the tree at runtime**: how many WORKER children, how deeply
to decompose, whether to add an AGGREGATE step, when to ask the
user for input. The recipe gives Marvin **whitelists and shape
hints**, not a step-by-step script.

Two consequences for recipe design:

- **Don't over-constrain.** A Marvin recipe lists *allowed*
  sub-task recipes, *allowed* expand-document paths, *disallowed*
  task-kinds — never a required child count, never a fixed
  child order, never a per-step orchestration. The PLAN-LLM
  composes.
- **Don't mix creative content with deterministic bookkeeping.**
  A Worker LLM whose system prompt asks it to "research the
  topic AND save the result to file X" will either hallucinate
  the save (no tool call, polite "done!" reply) or burn its
  whole tool-iteration budget retrying. Both observed in live
  runs. Persist via `postActions` (deterministic, engine-side)
  — not via prompt-engineered tool calls inside the worker.

## Valid TaskKinds (hard whitelist)

A Marvin recipe may only reference these TaskKind values in the
KIND-block skeletons of its `promptPrefix`. The runtime PLAN
parser rejects anything else; **the validator rejects unknown
kinds at recipe-load time, before Marvin runs.**

- `PLAN`
- `WORKER`
- `EXPAND_FROM_DOC`
- `USER_INPUT`
- `AGGREGATE`

If the user's request seems to ask for a kind not in this list
(e.g. "expand from a prompt"), restructure: a PLAN node can emit
N WORKER children directly without an intermediate "expand"
step. **Do not invent new kinds.** Common LLM mistake:
`EXPAND_FROM_PROMPT` does not exist.

## Mandatory recipe fields

A Marvin recipe MUST declare:

- `name`: kebab-case identifier.
- `engine: marvin`
- `params.rootTaskKind: PLAN`
- `promptPrefix`: a non-blank string — the PLAN-LLM instruction.
  This is a Pebble template (tier/model/mode variables available).

## Params block — constraint knobs

The `params` block carries Marvin's runtime constraints:

- `allowedSubTaskRecipes`: list of recipe names the PLAN may
  spawn as WORKER children. **Whitelist, not requirement** — the
  PLAN-LLM picks freely from it; not every entry must appear.
  Every entry MUST resolve to a real project recipe (the
  validator rejects inventions).
- `recipesOnlyViaExpand`: recipes that appear ONLY inside an
  EXPAND_FROM_DOC `childTemplate` (typical: chapter-loop).
- `allowedExpandDocumentRefPaths`: document paths the
  EXPAND_FROM_DOC may iterate over.
- `disallowedTaskKinds`: explicitly forbid certain TaskKinds.
  **Almost always empty.** Set ONLY when the user explicitly
  said "no synthesis" or the recipe is a pure data-gather
  pipeline. **NEVER set `[AGGREGATE]` as a default precaution**
  — that forces the PLAN-LLM to stuff synthesis into a single
  WORKER, recreating exactly the creative-vs-bookkeeping mix
  Marvin is supposed to avoid. When the user wants a "report",
  "summary", "consolidated answer" — AGGREGATE is the right
  TaskKind, do NOT disallow it.
- `defaultExecutionMode`: `SEQUENTIAL` for pipelines that build
  on each other, `PARALLEL` for independent phases.
- `maxPlanCorrections`: default 2 — the PLAN-LLM gets that many
  re-prompts to fix validation failures.

## Two separate concerns: creative synthesis vs. file persistence

Recipes commonly need both of these — keep them as two distinct
mechanisms.

### Creative synthesis → AGGREGATE node

When the deliverable is "combine N worker outputs into one
coherent text", use an **AGGREGATE** child. AGGREGATE is a
built-in TaskKind: Marvin invokes its own LLM (configurable
model alias) to synthesize the prior siblings' artifacts into a
single text result stored under `artifacts.summary`.

AGGREGATE characteristics:
- LLM-driven, creative.
- Reads previous siblings' artifacts automatically.
- Output: `summary` field (string).
- Optional `taskSpec.prompt` overrides the default synthesis prompt.
- Optional `taskSpec.maxOutputChars` caps the size.

AGGREGATE is **not** a recipe — don't list it in
`allowedSubTaskRecipes`. It's a TaskKind the PLAN-LLM can emit
directly.

### File persistence → postActions

When the deliverable must end up at a specific document path,
attach a **`postActions`** block to the **node** whose result
should be persisted. The engine executes postActions
deterministically after the node reaches DONE — **no LLM
involvement, no tool call, no hallucination risk.**

### Where postActions live (HARD RULE)

postActions belong **inside a node's `taskSpec`**, NOT at the
recipe root. The runtime engine only reads
`node.taskSpec.postActions`. A `postActions:` block at recipe
root level is **silently dropped at runtime** — and the
validator rejects it at recipe-load time so the LLM gets
explicit feedback.

✅ **Correct** (postActions inside a node's taskSpec, written into
the promptPrefix as part of the KIND-block JSON):

```yaml
promptPrefix: |
  Emit one AGGREGATE child as the LAST child:
  {"taskKind":"AGGREGATE",
   "goal":"Synthesize the final report.",
   "taskSpec":{
     "prompt":"Verdichte die Recherche zu einem Bericht.",
     "postActions":[
       {"tool":"doc_write_text",
        "args":{
          "path":"research/{{ process.goal | slug }}/report.md",
          "content":"{{ node.result }}"}}]}}
```

❌ **Wrong** (postActions at recipe root — validator rejects):

```yaml
name: deep-research
engine: marvin
promptPrefix: |
  ...
postActions:                          # <- WRONG, rejected
  - tool: doc_write_text
    args: { ... }
```

### postAction object shape

Each postAction is an object with two keys:

- `tool` — string, the operation name. Currently supported:
  - `doc_write_text` — upsert by path (find → update, else
    create).
  - `doc_create_text` — alias for `doc_write_text`.
- `args` — object with the tool-specific arguments:
  - `path` (required, string) — project-relative document path.
  - `content` (required, string) — the body to write.
  - `title` (optional, string) — sets/updates the document title.

The keys are **`tool` and `args`**, NOT `toolName` and `params`.
The engine is tolerant of `toolName`/`params` as fallbacks for
LLM-friendliness, but the canonical form is `tool` + `args`.
Slart-generated recipes should use the canonical form.

### postAction template variables (Pebble syntax)

All string args (`path`, `content`, `title`) are rendered through
the project's Pebble template engine. Available context:

- `{{ node.result }}` — the node's `result` artifact (set by the
  worker LLM on DONE), falling back to `summary` (AGGREGATE) or
  `partialResult` (NEEDS_SUBTASKS).
- `{{ node.summary }}` — the node's `summary` artifact (set by
  AGGREGATE).
- `{{ node.goal }}` — the node's goal text.
- `{{ process.goal }}` — the root process goal.
- `{{ process.id }}` — the process id.

For URL-safe path segments, use the `| slug` filter:

```yaml
path: "research/{{ process.goal | slug }}/report.md"
```

The `slug` filter lowercases, replaces non-alphanumeric runs
with `-`, and trims edge hyphens. So
`"Kernfusion in Europa 2025"` becomes `"kernfusion-in-europa-2025"`.

**Use `{{ ... }}`, NOT `${ ... }`.** The substitution engine is
Pebble (same as the rest of the project's templating). Mustache-
or-shell-style placeholders are not rendered.

### Common variable-name mistakes (LLM-trap list)

These look plausible but **render to empty strings at runtime**
and silently produce empty files. The validator catches the
known traps:

- ❌ `{{ aggregate.result.text }}` — there is no `aggregate`
  root. Use `{{ node.summary }}` on the AGGREGATE node.
- ❌ `{{ worker.reply }}` / `{{ worker.output }}` — no
  `worker` root. Use `{{ node.result }}`.
- ❌ `{{ process.recipe.yaml }}` / `{{ process.recipe.X }}` —
  recipes don't reflect their own definition into the render
  context. Don't try to write the recipe-yaml as an artefact.
- ❌ `{{ children.0.reply }}` — no child indexing. Use
  AGGREGATE to combine children, then `{{ node.summary }}`.
- ❌ `{{ input.topic }}` / `{{ input.X }}` — there is no
  `input` root. The user-provided topic is the process goal:
  use `{{ process.goal }}` or `{{ process.params.topic }}`.
- ❌ `{{ tasks.<name>.output.X }}` / `{{ tasks[…].output }}` —
  no `tasks` root, no cross-node addressing. Each node sees
  its own `node.*` plus `process.*`; cross-node data flow goes
  through AGGREGATE (which auto-reads prior siblings).

The only valid roots are: `node`, `result` (alias for `node`),
`process` (with `goal` / `id` / `params.<key>`), and inside an
EXPAND_FROM_DOC childTemplate `item`.

### postActions ALWAYS live inside taskSpec — never at YAML level

The validator rejects ANY `postActions` key found by walking
the recipe YAML structure. The only valid place to declare
postActions is **inside the KIND-block JSON skeleton embedded
in the promptPrefix string**, like this:

```yaml
promptPrefix: |
  ...
  {"taskKind":"AGGREGATE", ...,
   "taskSpec":{
     "prompt":"...",
     "postActions":[ {"tool":"doc_write_text","args":{...}} ]
   }}
```

❌ Wrong (postActions as a recipe-YAML field — at root or
under params, or anywhere else):

```yaml
name: deep-research
engine: marvin
params:
  postActions:                   # ← rejected by validator
    - action: SAVE_OUTPUT
postActions:                     # ← rejected by validator
  - tool: doc_write_text
```

The reason: Marvin's runtime reads `node.taskSpec.postActions`
from each spawned node, NEVER from the recipe YAML. A YAML-level
postActions block is silently dropped. Embed it into the
KIND-block JSON skeleton in promptPrefix instead — that JSON
becomes the node's taskSpec at spawn time, carrying the
postActions with it.

postActions failures don't unwind the node — the node stays
DONE, the failure is logged. This matches the spirit of "we
already have the artifact; persistence is best-effort".

### What postActions should NOT do

Persist **only the user-requested artefacts** — typically one
or two paths the user named or that flow from the recipe's
purpose. Do **NOT**:

- save the recipe yaml back to its own location (Slart already
  persisted it during PERSISTING; rewriting it from a postAction
  produces an empty file and silently overwrites the working
  recipe);
- save intermediate scratchpad data unrelated to the user's
  deliverable;
- write to system-reserved paths like `recipes/_user/…`,
  `_slart/…`, `_vance/…`, `_tenant/…`. Those are managed by
  the engines themselves.

One postAction per artefact the user actually asked for.
Resist the temptation to "be helpful" with extras.

## Standard shapes

### Shape A — Single worker, one file

The smallest useful Marvin recipe: one WORKER that produces a
text reply, one postAction that writes it.

```yaml
name: capture-summary
engine: marvin
params:
  rootTaskKind: PLAN
  allowedSubTaskRecipes: [marvin-worker]
promptPrefix: |
  Emit one WORKER child:
  {"taskKind":"WORKER",
   "goal":"Write a short summary of '{{ process.goal }}' (200-300 words).",
   "taskSpec":{
     "recipe":"marvin-worker",
     "postActions":[
       {"tool":"doc_write_text",
        "args":{
          "path":"summaries/{{ process.goal | slug }}.md",
          "content":"{{ node.result }}"}}]}}
```

The worker stays pure-creative ("write a 200-300 word summary").
The engine writes the file deterministically.

### Shape B — Research pipeline with AGGREGATE + final write

The canonical multi-source research recipe. PLAN spawns N
research workers, then ONE AGGREGATE synthesizes them, and the
AGGREGATE's postAction writes the final file.

```yaml
name: deep-research
engine: marvin
params:
  rootTaskKind: PLAN
  defaultExecutionMode: SEQUENTIAL
  allowedSubTaskRecipes: [web-research]
promptPrefix: |
  PLAN: decompose the topic '{{ process.goal }}' into 4-6
  research aspects (history, technology, pros, cons, safety,
  future, ...). Emit ONE WORKER child per aspect with
  recipe=web-research:
  {"taskKind":"WORKER",
   "goal":"<aspect description>",
   "taskSpec":{"recipe":"web-research"}}

  Then emit ONE AGGREGATE child as the LAST child. Put the
  postActions block on the AGGREGATE's taskSpec — it will write
  the synthesized report to a file after AGGREGATE finishes:
  {"taskKind":"AGGREGATE",
   "goal":"Synthesize a 1500-2000 word report from the prior siblings.",
   "taskSpec":{
     "prompt":"Verdichte die Recherche zu einem zusammenhängenden Bericht von 1500-2000 Wörtern mit Quellenangaben.",
     "maxOutputChars":12000,
     "postActions":[
       {"tool":"doc_write_text",
        "args":{
          "path":"research/{{ process.goal | slug }}/report.md",
          "content":"{{ node.summary }}",
          "title":"Research Report — {{ process.goal }}"}}]}}
```

Note: web-research workers do NOT need file-write knowledge.
They just summarize sources in their reply. The AGGREGATE pulls
them together. The postAction on the AGGREGATE writes the final
file. Three clean separations, three clean failure modes.

### Shape C — Document-driven expansion (advanced)

When the deliverable is "do work per item in a list document"
(per chapter, per source, per requirement), use
EXPAND_FROM_DOC. Each spawned child can have its own postAction
declared in the childTemplate.

```yaml
allowedSubTaskRecipes: [marvin-worker]
recipesOnlyViaExpand: [marvin-worker]
allowedExpandDocumentRefPaths: ["essay/outline.md"]
promptPrefix: |
  Emit ONE EXPAND_FROM_DOC child:
  {"taskKind":"EXPAND_FROM_DOC",
   "goal":"Per-chapter content from the outline.",
   "taskSpec":{
     "documentRef":{"path":"essay/outline.md"},
     "treeMode":"FLAT",
     "childTemplate":{
       "taskKind":"WORKER",
       "recipe":"marvin-worker",
       "goal":"Write the chapter: {{ item.text }}",
       "postActions":[
         {"tool":"doc_write_text",
          "args":{
            "path":"essay/chapters/{{ node.goal | slug }}.md",
            "content":"{{ node.result }}"}}]}}}
```

Each per-chapter child inherits the postAction from the
childTemplate and writes its own file. No AGGREGATE needed
unless you want a final consolidation.

## When AGGREGATE fits vs. when postActions alone is enough

| Scenario | Mechanism |
|---|---|
| One worker → one file | postAction on the worker |
| Many workers → one synthesized file | AGGREGATE + postAction on the AGGREGATE |
| Many workers → many independent files | postAction per worker (or in EXPAND childTemplate) |
| Many workers → "report this back to the user, no file" | AGGREGATE alone (its summary surfaces in summarizeForParent) |
| One worker → multiple files | postActions list with multiple entries on the worker |

## Worker capabilities — short reference

Quick reminder of what stock worker recipes can/can't do (full
catalog still lives in the project recipe-list at runtime):

- **`marvin-worker`** — Ford-based, has `doc_*` and the
  structured Marvin output contract. Good general-purpose
  worker. Can also handle file writes via its prompt, but
  **prefer postActions** for persistence — keeps the LLM
  creative-focused.
- **`web-research`** — Ford-based summary worker. `web_search`
  + `web_fetch` only. **Returns text in reply, never writes
  files.** Pair with postAction (or AGGREGATE+postAction) for
  persistence.
- **`analyze`**, **`code-read`** — text-reply workers. Same
  pattern as `web-research`.

The bundled validator only checks recipe existence
(`marvin-recipe-allowed-recipes-exist`), TaskKind validity, and
absence of recipe-root postActions. Capability matching is the
recipe author's job, supported by this catalog.

## When a Marvin recipe fits

- **Task decomposition with dynamic size**: "research aspect X,
  then aspect Y, then synthesize" — count emerges from the
  PLAN-LLM's decomposition.
- **Document-driven expansion**: "one chapter per outline item".
- **Mixed creative-then-deterministic pipelines**: research +
  synthesis + file write (Shape B).

## When a Marvin recipe does NOT fit

- **Strictly linear pipelines with fixed phases**: use Vogon.
- **Multi-perspective evaluation**: use Zaphod council.
- **Single-shot conversation**: use a plain Ford recipe.
