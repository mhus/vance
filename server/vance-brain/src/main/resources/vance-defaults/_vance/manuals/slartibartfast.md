---
triggers: slart, slartibartfast, plan architect, plan-architect, generate a recipe, generate strategy, strategie generieren, meta-recipe, build a plan, design a workflow, vogon strategy, marvin recipe, zaphod council, magrathea workflow, state machine, workflow with gates, no recipe fits, custom multi-phase plan, school essay, multi-chapter report
summary: When and how to delegate to Slartibartfast — the plan-architect. It is NOT a catch-all fallback; it generates one of five fixed shapes (Vogon strategy / Marvin recipe / Zaphod council / JS script / Magrathea workflow) and is reached explicitly, not by default routing.
---
# Slartibartfast — the plan-architect

Slartibartfast ("Slart") is a **plan-architect**, not a
general-purpose worker and not the catch-all fallback for unmatched
tasks. It does one narrow thing: take a free-text goal, frame it,
gather the project's installed manuals/kits as evidence, and emit
**one of five fixed shapes** — then (by default) run it and validate
the output.

| Preset | Output shape | Runs on |
|---|---|---|
| `slartibartfast` | Vogon plan (state machine run for a person) | Vogon |
| `marvin-architect` | Marvin recipe (dynamic task-tree) | Marvin |
| `zaphod-architect` | Zaphod council (multi-persona panel) | Zaphod |
| `slart-script-author` | JS orchestration script | Hactar |
| `magrathea-architect` | Magrathea workflow (same grammar, headless) | — (author-only; run via `workflow_start`) |

**`slartibartfast` and `magrathea-architect` write the same grammar.**
Since the merge there is one plan format; the two presets differ in the
advice they give, because the jobs differ. Choose by *who the plan runs
for*:

- **`slartibartfast` (Vogon plan)** — the person starts it and waits on
  it. It can ask them questions while it runs and gives the result back
  in the conversation. Built around judgements: a worker produces, another
  scores, the plan loops until it is good enough. Slart can run it.
- **`magrathea-architect` (workflow)** — nobody is waiting. It is started
  by a scheduler, an event, a hook or a tool, and anything it needs from a
  person goes to the inbox. Built around things that must happen:
  commands, tools, retries, timers. **Author-only** — it validates and
  persists to `_vance/workflows/<name>.yaml`, and you start it afterwards
  with `workflow_start`.

**Running a plan is a separate question from writing one** — and it is
not always `workflow_start`. A plan somebody is waiting for runs on the
`vogon` recipe, from any path, and answers back into the conversation.
See `manual_read('plans')`.

Both may use every task type. The split is guidance for the author, not a
restriction on the plan.

Because Slart reads installed kits/manuals as evidence, the plan it
generates is automatically kit-aware.

## How Slart is (and isn't) reached

`DELEGATE` routing does **not** land on Slart by default:

- **`DELEGATE` with a matching recipe** → that recipe runs.
- **`DELEGATE`, no match, no plan-architect trigger** → falls
  through to the **default recipe (ford)**. *Not* Slart.
- **`DELEGATE` on an explicit plan-architect trigger**
  (`slart`, `plan architect`, `generate strategy`, `vogon`,
  `meta-recipe`) with no matching recipe → tenant fallback recipe
  (`slart-and-run`).
- **`DELEGATE` with `preset="slartibartfast"`** (or the other three
  presets above) → Slart directly, with the chosen output shape.

So: to get a bespoke plan you almost always set the `preset`
yourself. Don't assume a bare no-preset DELEGATE will produce one.

## When to reach for it

Reach for Slart when a task is substantial enough to deserve its
**own tailored multi-phase plan** and no pre-built recipe fits:

- School essays, multi-chapter reports, structured long-form
  documents (research → outline → chapters → review → consolidate).
- Research deliverables that need a repeatable, phased pipeline.
- A council of personas you want to consult repeatedly (Zaphod).
- A one-off orchestration script over tools/APIs (script-author).
- A reusable **process nobody waits on** — scheduled, event- or
  hook-triggered, with retries, timers or sub-workflows
  (`magrathea-architect`).

An active SKILL that names a `preset` wins — follow it verbatim;
the skill author knows the task shape better than generic routing.

## When NOT to reach for it

- **A bundled recipe already fits** — `web-research`, `analyze`,
  `code-read`, `coding`, `quick-lookup`, `marvin`, a Vogon strategy
  like `waterfall-feature`. Use it directly; Slart adds 60-180s of
  planning overhead.
- **One-shot, predictable work** you can do inline or in a single
  Ford/analyze turn. A designed plan document is wasted on it.
- **Open-ended, no clear endpoint** — that's Trillian's shape, not
  a finishable recipe.
- **Editing an existing recipe/plan** — tweak its YAML with
  `doc_edit`; Slart is for initial creation, not maintenance.

## Related

- `manual_read('processes')` — spawning/inspecting sub-processes.
- Spec: `specification/public/slartibartfast-engine.md`.
