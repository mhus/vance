# Marvin Recipe — Shape

This manual describes what a Marvin Recipe is structurally.
Slartibartfast's GATHERING ingests it as engine-bundled evidence
so DECOMPOSING can tie subgoals to concrete claims even when no
project-specific kit is installed.

## What is a Marvin Recipe

A Marvin Recipe defines a **task-decomposition pipeline**: a
PLAN node receives a goal, emits N children of declared kinds
(WORKER / EXPAND_FROM_DOC / AGGREGATE), each child can in turn
trigger further sub-decompositions. The recipe constrains what
the PLAN-LLM may emit so the runtime stays bounded.

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
  spawn as WORKER children. Every entry MUST resolve to a real
  project recipe (the validator rejects inventions).
- `recipesOnlyViaExpand`: recipes that appear ONLY inside an
  EXPAND_FROM_DOC `childTemplate` (typical: chapter-loop).
- `allowedExpandDocumentRefPaths`: document paths the
  EXPAND_FROM_DOC may iterate over.
- `disallowedTaskKinds`: set `[AGGREGATE]` when the plan needs a
  WORKER-style aggregator (instead of Marvin's built-in summary).
- `defaultExecutionMode`: `SEQUENTIAL` for pipelines that build
  on each other, `PARALLEL` for independent phases.
- `maxPlanCorrections`: default 2 — the PLAN-LLM gets that many
  re-prompts to fix validation failures.

## promptPrefix — KIND blocks

The `promptPrefix` carries one KIND block per recipe in
`allowedSubTaskRecipes`. Each KIND block:

- Names which recipe the child uses.
- Contains a literal JSON skeleton for the child's spawn.
- Describes what the child produces.

The number of KIND blocks MUST equal the size of
`allowedSubTaskRecipes`. The PLAN-LLM is instructed to emit
EXACTLY that many children in KIND order.

## Completeness requirement

A Marvin recipe MUST drive the user's request to a *final
deliverable*. If outline → chapters → aggregator phases are all
needed, the recipe MUST wire all three. Stopping mid-pipeline is
a hard failure.

## Worker capability catalog — pick the right recipe per node

This is the most important section. The Marvin-PLAN-LLM gets a
`steerContent` per WORKER child; that instruction must match what
the chosen worker recipe can actually *do*. The default mismatch
mode is: the PLAN-LLM tells a summary-only worker to "save to
file X", the worker has no write-tools, it hallucinates success in
its reply text, and the downstream EXPAND_FROM_DOC / read-step
fails with `document not found`.

To avoid this, design the recipe with this catalog in mind:

### Recipes that DO persist files (have `doc_*` tools)

- **`marvin-worker`** — Ford-engine generalist with **the full
  `doc_*` toolset** AND the Marvin structured-output contract
  (`DONE / NEEDS_SUBTASKS / NEEDS_USER_INPUT / BLOCKED_BY_PROBLEM`).
  This is the **only** stock recipe that reliably writes files
  when instructed to. Use it for **every** node whose deliverable
  is a persisted document.
- Custom project-recipes (`write-section`, `compile-report`, …)
  may also have `doc_*` access — check the project's recipe list.

### Recipes that DO NOT persist files (reply-only summary workers)

- **`web-research`** — Ford-engine summary worker. Tools: `web_search`,
  `web_fetch`. **No `doc_*` tools.** The contract is "summarise
  findings in your reply"; the worker returns a text answer, never
  a written file. Telling it "save to `path.json`" produces a
  polite hallucination, not a file.
- **`analyze`** — same shape: text reply, no file writes.
- **`code-read`** — reads code, returns analysis as reply text.
- **`ford`** (raw) — generalist, single-shot, replies in text.
- **`jeltz`** — schema-validated JSON in reply, not a file.

### The file-output pattern

If your recipe's deliverable is a persisted file (e.g. user said
"save the report under `research/<topic>/report.md`"), the PLAN
MUST include a `marvin-worker` WORKER node whose `steerContent`
explicitly says **"write the result to `<path>` via the
`doc_write_text` tool"**. Do NOT rely on a summary worker
(`web-research` / `analyze` / `code-read`) to also do the writing
— it cannot.

Two valid shapes for a research → file pipeline:

**Shape 1 — separate collect + write nodes.** Use `web-research`
to collect into its reply, then a `marvin-worker` that reads the
previous sibling's reply (from the PLAN-injected sibling-summary
block) and writes the file:

```yaml
allowedSubTaskRecipes: [web-research, marvin-worker]
children:
  - WORKER recipe=web-research
    steerContent: |
      Recherchiere Quellen zum Thema X. Liste in deiner Antwort
      pro Quelle: URL, Titel, Kernaussage.
  - WORKER recipe=marvin-worker
    steerContent: |
      Lies die Vorgänger-Antwort. Wandle sie in JSON um:
      {sources:[{url,title,summary}, …]}. Schreibe das per
      `doc_write_text(path="research/sources.json", content=…)`.
```

**Shape 2 — single marvin-worker doing both.** When the task is
small enough, skip the split:

```yaml
allowedSubTaskRecipes: [marvin-worker]
children:
  - WORKER recipe=marvin-worker
    steerContent: |
      Recherchiere Quellen zum Thema X via `web_search`. Schreibe
      die gefundenen Quellen per `doc_write_text(path="research/
      sources.json", content=…)` als JSON.
```

### Heuristic for the architect

When the user's request mentions a path output (`"speichere
unter X.md"`, `"writes to Y.json"`, …):

1. The node whose deliverable IS that file MUST use `marvin-worker`
   (or a custom recipe with confirmed write-tool access).
2. The `steerContent` MUST contain an explicit `doc_write_text(…)`
   instruction with the literal path.
3. NEVER attach a "save to file" suffix to a `web-research` /
   `analyze` / `code-read` steerContent — those workers cannot
   honour it.

### Validation hint

`marvin-recipe-allowed-recipes-exist` already checks that every
entry in `allowedSubTaskRecipes` resolves to a real recipe.
Capability matching (write-tool? structured contract?) is
**author responsibility** for v1 — get it right in PROPOSING by
following this catalog.

## When a Marvin recipe fits

- **Task decomposition with dynamic size**: "write a chapter per
  outline item" — chapter count emerges from the outline.
- **Plan-then-execute** missions where the PLAN-LLM benefits from
  a structured template (KIND blocks) over free-form planning.

## When a Marvin recipe does NOT fit

- **Strictly linear pipelines with fixed phases**: use Vogon.
- **Multi-perspective evaluation**: use Zaphod council.
- **Single-shot conversation**: use a plain Ford recipe.
