# Marvin v2 Recipe — Shape

This manual describes what a Marvin v2 recipe looks like.
Slartibartfast's GATHERING ingests it as engine-bundled evidence
so DECOMPOSING can tie subgoals to concrete claims even when no
project-specific kit is installed.

## Marvin v2 in one sentence

Every Marvin node is an **autonomous marvin-worker** that walks a
five-phase state-machine (**SCOPE → REFLECT → POST_CHILDREN →
CONCLUDE → VALIDATE**). The recipe does NOT prescribe a fixed
tree — the worker decides at runtime when to call a specialist
recipe, decompose into children, ask the user, or conclude.

The Tree IS the Plan. Every LLM call receives a live snapshot of
the whole tree as context.

## What a Marvin recipe looks like

```yaml
name: deep-research
description: |
  Systematic web research with synthesized report.
engine: marvin
params:
  language: de
  availableRecipes:
    - web-research
  reflectMaxIterations: 3
  validateMaxIterations: 2
promptPrefix: |
  Du sollst eine Recherche zum Thema {{ process.goal }} durchführen
  und einen zusammenhängenden Bericht erstellen.

  Vorgehen:
  - Beleuchte das Thema entlang folgender Aspekte:
    - history: Historie und politischer Kontext.
    - tech: Aktueller Stand der Technologie.
    - ecology: Ökologische Auswirkungen.
  - Nutze web-research via CALL_RECIPE, um Material zu sammeln.
  - Verdichte zu einem Bericht von 1500-2000 Wörtern.
  - Sprache: de.

  Wenn dein Bericht fertig ist (in CONCLUDE), gib zusätzlich
  folgende postActions zurück, damit der Bericht persistiert wird:
    [
      {"tool":"doc_write_text",
       "args":{
         "path":"research/{{ process.goal | slug }}/report.md",
         "content":"{{ node.result }}"}}
    ]
```

Three building blocks:

- **engine: marvin** — fixed.
- **params** — runtime knobs:
  - `language` — `"de"` or `"en"`; the worker uses this for its
    output.
  - `availableRecipes` — list of recipe names the worker may
    invoke via CALL_RECIPE. Empty list = no specialist tools;
    worker answers directly. **Recipes whose engine is `marvin`
    are not allowed here** (cross-Marvin nesting is blocked).
  - `reflectMaxIterations`, `validateMaxIterations`,
    `concludeMaxRetries`, `maxTreeDepth` — phase caps (optional;
    defaults: 3 / 2 / 2 / 5).
- **promptPrefix** — narrative goal context for the root worker.
  Pebble template (so `{{ process.goal }}` etc. render at runtime).
  Tell the worker WHAT to achieve and HOW (aspects, structure,
  output path). Do NOT prescribe a fixed tree shape, KIND blocks
  or child counts — the worker decides those.

## The five phases — what the worker does

The engine drives every WORKER node through:

1. **SCOPE** — initial decision: CALL_RECIPE, PROCEED_TO_CONCLUDE,
   NEEDS_SUBTASKS, NEEDS_USER_INPUT, or BLOCKED_BY_PROBLEM.
2. **REFLECT** — after a CALL_RECIPE, decides again. Capped at 3
   CALL_RECIPEs per node.
3. **POST_CHILDREN** — after NEEDS_SUBTASKS-spawned children all
   finish, the parent re-enters here to synthesise.
4. **CONCLUDE** — produces the final Markdown answer + optional
   `postActions` for persistence.
5. **VALIDATE** — critical self-review. PASS ends the node;
   RETRY_CONCLUDE / NEED_MORE_DATA loop back; HARD_FAIL fails.

The worker also sees a **LIVE PLAN snapshot** of the entire tree
every turn — it knows what siblings/cousins are doing and won't
re-spawn already-covered subgoals.

## TaskKinds — only three exist

A Marvin v2 recipe references task kinds **only** in narrative
hints inside promptPrefix (e.g. "spawne via NEEDS_SUBTASKS").
The three legal kinds:

- **WORKER** — the default; every NEEDS_SUBTASKS-spawned child is
  a WORKER and runs the same 5-phase state machine.
- **EXPAND_FROM_DOC** — deterministic fanout from a list / tree /
  records document. Used when a structured document already holds
  the plan (an outline, a requirements list). No LLM call.
- **USER_INPUT** — inbox-item wait-point. Spawned implicitly when
  the worker emits NEEDS_USER_INPUT.

PLAN and AGGREGATE from v1 are gone. The worker is its own
planner (SCOPE/REFLECT decides decomposition); POST_CHILDREN
replaces AGGREGATE.

## availableRecipes — the worker's toolbox

The worker can only CALL_RECIPE entries in this whitelist. The
specialist runs in its native mode — Marvin does NOT layer its
output contract on top of it. The reply lands as a USER message
in the worker's memory before REFLECT.

Pick specialist recipes that match the goal:
- **`web-research`** — gather web sources, returns markdown summary.
- **`analyze`** — read project files, return analysis.
- **`code-read`** — read source code, return summary.

Recipes whose engine is `marvin` cannot be CALL_RECIPE targets
(v1 block — prevents unbounded nesting).

## postActions — engine-side persistence

The worker emits `postActions` inside its CONCLUDE JSON. They
run deterministically after VALIDATE passes, with no LLM
involvement and no tool-call risk. Supported tools:

- `doc_write_text` — upsert by path (find → update, else create).
- `doc_create_text` — alias.

### postAction shape

```json
{"tool":"doc_write_text",
 "args":{
   "path":"research/{{ process.goal | slug }}/report.md",
   "content":"{{ node.result }}",
   "title":"Optional"}}
```

### postAction Pebble variables

All string args render through Pebble with this context:

- `{{ node.result }}` — the node's CONCLUDE result.
- `{{ node.goal }}` — the node's goal text.
- `{{ process.goal }}` — the process's root goal.
- `{{ process.id }}` — process id.
- `| slug` filter — URL-safe slug.

**Invalid roots** (LLM trap list): `aggregate.*`, `worker.*`,
`children.*`, `input.*`, `tasks.*`, `process.recipe.*`. The
validator rejects these at recipe-load.

### postAction paths

`path` must NOT start with: `recipes/`, `_user/`, `_vance/`,
`_slart/`, `_tenant/`, `_zaphod-drafts/`, `_vogon-drafts/`.
Use fresh folders: `research/`, `essays/`, `decisions/`,
`reports/`, `documents/`.

### Where postActions go

**In the worker's CONCLUDE JSON** — not in the recipe YAML.
Recipe authors can include the postActions template literally in
promptPrefix (the worker copies them verbatim into the CONCLUDE
output), but the canonical execution path is engine-side after
the worker emits them. Recipe-root postActions blocks are
rejected by the validator.

## How to compose a recipe

For most goals, one paragraph of narrative + an availableRecipes
list is enough:

```yaml
name: simple-research
engine: marvin
params:
  language: de
  availableRecipes: [web-research]
promptPrefix: |
  Recherchiere zu {{ process.goal }} und schreibe einen Bericht
  (1500-2000 Wörter). Nutze web-research via CALL_RECIPE. Sprache: de.

  Persistiere den fertigen Bericht in CONCLUDE über postActions:
  [{"tool":"doc_write_text",
    "args":{"path":"research/{{ process.goal | slug }}/report.md",
            "content":"{{ node.result }}"}}]
```

The worker plans the aspects in SCOPE, calls web-research as
needed, synthesises in CONCLUDE, the engine writes the file
after VALIDATE passes. No KIND blocks, no AGGREGATE choreography,
no fixed child counts.

## When Marvin fits

- **Dynamic decomposition** — "research X then Y then synthesise"
  where the actual subgoal count depends on the topic.
- **Tool-augmented research** — gather via specialist recipes,
  synthesise.
- **Mixed creative + persistent** — research + write-to-file.

## When Marvin does NOT fit

- **Strictly linear pipelines with fixed phases** — use Vogon.
- **Multi-perspective evaluation** — use Zaphod council.
- **Single-shot conversation** — use a plain Ford recipe.
