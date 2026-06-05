# Vogon Strategy Recipe — Shape

This manual describes what a Vogon Strategy Recipe is structurally.
Slartibartfast's GATHERING ingests it as engine-bundled evidence
so DECOMPOSING can tie subgoals to concrete claims even when no
project-specific kit is installed.

## What is a Vogon Strategy

A Vogon Strategy is a **strict sequential workflow** — phases run
in order, each phase has a worker recipe, gates between phases
enforce completion, scorers can branch on quality, loops can
re-run phases until a threshold is met. The recipe is a one-shot
plan for *this specific deliverable*; the strategy IS the mission.

## Mandatory recipe fields

A Vogon recipe MUST declare:

- `name`: kebab-case identifier.
- `engine: vogon`
- `params.strategyPlanYaml`: an inline YAML string containing the
  strategy specification (parsed by `StrategyResolver.parseStrategy`).

## Strategy-yaml shape

The `params.strategyPlanYaml` value parses as:

- `name`: strategy name (can differ from the recipe name).
- `version: "1"`
- `phases`: a non-empty list of phase specifications.

Each phase declares:

- `name`: phase identifier (kebab-case).
- `worker`: name of an existing project recipe — typically `ford`,
  `marvin-worker`, `analyze`, `code-read`, or a task-specific
  recipe from the project. Tool names (e.g. `doc_create`) are
  NOT valid worker values; the validator rejects them.
- `workerInput`: the prompt the worker receives.
- `gate.requires`: list of completion markers that must be set
  before this phase runs.

Optional per-phase:
- `scorer`: branch decision based on output quality.
- `loop`: repeat the phase until a condition is met.
- `postActions`: tool calls run after the worker (typically
  `doc_create` to persist artefacts).

## Phase chaining

Before each phase's worker runs, Vogon injects a discovery block
listing every completed predecessor with its draft-path
(`_vogon-drafts/<process>/<phase>.md`). Workers read predecessors
via `doc_read` (full) or `doc_summary` (1-3 sentence recap).

## postActions — practically mandatory for content pipelines

`postActions` is technically optional in the YAML schema, but for
**any phase that produces a meaningful artefact** (essay outline,
chapter text, research synthesis, refactor plan, …) the recipe
MUST include a `postActions` block that persists the worker's
output to a known project path via `doc_create`.
Without postActions, the phase's output lives
only as an inline string in the Vogon state — workers in later
phases see at best a 1-3 sentence summary in the discovery block,
NOT the full content. Pipelines that need the full predecessor
output (e.g. assemble-essay reading chapter drafts) break
silently.

The Slart VALIDATING phase enforces this for path-criteria the
user explicitly mentioned — but the recipe author should add
post-Actions by default, not wait for validator pressure.

### postActions YAML shape

```
postActions:
  - tool: doc_create
    args:
      path: essay/outline.md
      kind: text
      content: ${worker.reply}
      title: "Essay-Outline"
```

`${worker.reply}` is substituted with the full worker output at
runtime. The path is a project-relative document path; choose a
convention up-front (`essay/<phase>.md`, `report/<section>.md`,
`refactor/<phase>.md`) so downstream phases can `doc_read` them
by name.

## Worker output sizing

A Vogon phase output of < 500 characters is almost always a
**worker failure mode** — the LLM emitted a tool-call instead of
direct text, or hit its tool-iteration budget without producing
content. Recipes that target longer outputs (chapter ≥ 1500 chars,
report-section ≥ 1000 chars) should encode the expectation:

- Set `workerInput` explicit: "Write at least N words …" / "The
  chapter must be 200-500 words …"
- For long-form content (chapters, multi-page reports), prefer
  `worker: marvin-worker` over `worker: ford` — marvin-worker is
  the structured Ford variant that decomposes long outputs into
  sub-tasks, giving more reliable token-output than a single Ford
  turn.

## Quality gates — the Lector loop pattern

For content phases where quality matters (essay chapters,
research synthesis), add a Lector-style review loop:

```
- name: draft-chapter-1
  worker: ford
  workerInput: |
    Write chapter 1 …
  postActions:
    - tool: doc_create            # doc_create upserts so revisions overwrite
      args:
        path: essay/chapters/01.md
        kind: text
        content: ${worker.reply}
  loop:
    maxIterations: 3
    until:
      requiresAny: ["chapter-1-approved"]
    subPhases:
      - name: review-chapter-1
        worker: ford
        workerInput: |
          Read essay/chapters/01.md via doc_read. Score 0-10 on
          (a) style match to Adams, (b) length ≥ 200 words,
          (c) plot consistency. Reply with a single JSON
          {"score": <int>, "feedback": "<short>"}.
        scorer:
          field: score
          cases:
            - when: { scoreAtLeast: 7 }
              action: { setFlag: "chapter-1-approved" }
            - when: { scoreBelow: 7 }
              action: { exitLoop: false }   # next iteration revises
```

The chapter-write phase runs, the review-phase scores, on
score≥7 the loop exits with the chapter approved; otherwise
the next iteration revises (the original draft is still at the
known path, the worker can `doc_read` + emit a revised version).

## Essay-pipeline example (with postActions)

```
name: my-essay-pipeline
version: "1"
phases:
  - name: generate-outline
    worker: ford
    workerInput: |
      Create a 3-chapter outline. Plot, key characters,
      satirical points per chapter.
    postActions:
      - tool: doc_create
        args:
          path: essay/outline.md
          kind: text
          content: ${worker.reply}
          title: "Essay-Outline"
    gate: { requires: [generate-outline_completed] }

  - name: draft-chapter-1
    worker: marvin-worker     # long-form content
    workerInput: |
      Read essay/outline.md via doc_read. Write chapter 1
      (200-500 words). Style: Douglas Adams.
    postActions:
      - tool: doc_create
        args:
          path: essay/chapters/01.md
          kind: text
          content: ${worker.reply}
    gate: { requires: [draft-chapter-1_completed] }

  # … draft-chapter-2, draft-chapter-3 analogously …

  - name: assemble-essay
    worker: ford
    workerInput: |
      Read essay/outline.md, essay/chapters/01.md,
      essay/chapters/02.md, essay/chapters/03.md via doc_read.
      Consolidate into essay/final-essay.md with chapter
      headings. Add a short intro paragraph.
    postActions:
      - tool: doc_create
        args:
          path: essay/final-essay.md
          kind: text
          content: ${worker.reply}
          title: "Adams-Style Essay"
    gate: { requires: [assemble-essay_completed] }
```

Notice the path convention: every phase writes to a predictable
location under `essay/`, so the final consolidation phase can
read all predecessors by name. The discovery block Vogon injects
covers it too, but explicit `doc_read` in the workerInput is
more reliable for long content.

## When a Vogon strategy fits

- **Linear pipelines**: research → outline → chapters →
  consolidation. Each phase consumes the previous, the order is
  data-driven, the deliverable is the final artefact.
- **One-shot missions** with a clear endpoint.
- **Quality gates**: scorer-driven retry loops (Lector pattern).

## When a Vogon strategy does NOT fit

- **Multi-perspective evaluation**: use a Zaphod council instead.
- **Reusable consultation**: Vogon strategies are one-shot — for
  panels you consult repeatedly, use a council.
- **Dynamic task decomposition**: when the plan shape can't be
  fixed in advance, use Marvin instead.
