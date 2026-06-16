# Zaphod Recipe — Shape

This manual describes what a Zaphod Recipe is structurally.
Slartibartfast's GATHERING ingests it as engine-bundled evidence
so DECOMPOSING can tie subgoals to concrete claims even when no
project-specific kit is installed.

Zaphod supports **two patterns** today (synonym: "modes" /
"Modi"); pick the one matching the user's request:

| Pattern | Shape | When |
|---|---|---|
| `council` | N heads, ONE round, synthesis | The user wants several perspectives on a question; nobody needs to react to anyone else. |
| `debate` | N heads, UP TO `maxRounds` with a between-round consensus check, then synthesis | The heads should *react* to each other — positions can shift, the room can converge. |

## What is a Zaphod Recipe

A Zaphod Recipe drives **N head sub-processes** against the same
input. In `council`, the heads run once in parallel (semantically
— sequentially in V1) and then a **synthesizer turn** combines
their outputs into one consolidated answer. In `debate`, each
round all heads see the previous-round replies of the *other*
heads; between rounds a small LightLlm-call ("consensus check")
decides whether the heads have converged. Synthesis runs once the
loop ends — either because consensus was reached or because
`maxRounds` was hit.

A Zaphod recipe is a *reusable asset* — the same recipe is
consulted repeatedly with different questions over its lifetime.

## Mandatory recipe fields

Every Zaphod recipe MUST declare:

- `name`: kebab-case identifier, unique within the project namespace.
- `engine: zaphod`
- `params.pattern`: `council` or `debate`.
- `params.heads`: a list of head specifications, 2 to 5 entries
  (debate REQUIRES at least 2 — a single-head debate is rejected
  at start).
- `params.synthesisPrompt`: instructions for the synthesizer turn.

For `debate` ONLY:

- `params.maxRounds` (optional, default 3, hard-cap 10): how many
  rounds at most before the synthesizer wraps up regardless of
  consensus. Pick 2 for cheap "first reaction" debates, 3 for the
  default, 4-5 for genuinely contested decisions where positions
  take longer to settle. Above 5 is almost always a sign the
  question is wrong, not the recipe.

## Per-head fields

Each entry in `params.heads` MUST carry:

- `name`: unique kebab-case role identifier (e.g.
  `security-reviewer`, `cost-optimist`, `pro`, `contra`).
- `recipe`: name of an existing project recipe — typically `ford`
  or a ford-style conversational variant. The recipe defines the
  head's execution shape; persona steers behaviour.
- `persona`: 1-3 sentences describing the head's perspective,
  bias, or expert role. Personae MUST be *distinct* across heads —
  generic descriptions like "a thoughtful assistant" collapse the
  council to one answer.

### Persona shape for `debate`

For `council`, personae are descriptive: "the optimist", "the
auditor", "the cost-conscious engineer". For `debate`, personae
MUST be *positional* — they take a stance the head can revise
under pressure. Good debate personae:

- name the position the head defends ("you argue FOR…" / "you
  argue AGAINST…"),
- explicitly allow position changes when a counter-argument is
  objectively stronger,
- forbid stylistic concessions that don't reflect real updates
  (no "yes, you're absolutely right" without substance).

A bad debate persona reads like a council persona ("you focus on
risks") — the head will not push back across rounds, and the
debate collapses to a slow council.

## Heads-count rules

- 2 heads minimum (below that there is nothing to synthesise; for
  `debate` this is enforced at start).
- 3-5 heads is the sweet spot for `council` (balanced breadth,
  manageable synthesis).
- 2-3 heads is the sweet spot for `debate` (the consensus check
  gets noisy with many heads — convergence becomes a partial
  question).
- Hard cap at 7 (the `ZaphodHeadsParser` rejects more).
- For decisions with truly more axes, split into multiple smaller
  councils rather than one large one.

## Synthesis-prompt rules

- Tell the synthesizer what to DO with the head outputs:
  prioritise / contrast / consolidate / decide.
- Reference heads by name so the synthesizer can ground each
  strand.
- Name the deliverable shape: a paragraph, a decision matrix, a
  recommendation with caveats.
- A vague "synthesise the outputs" is not enough — the
  synthesizer needs a concrete task.

## Synthesizer has NO TOOLS — engine persists deterministically

The Zaphod synthesizer is a direct LLM call with **structured
JSON output** (`{title, summary, synthesisMarkdown}`). It does
NOT have access to file-writing tools like `doc_create`. The
engine itself parses
the structured reply and writes the synthesis to a project
document at the resolved output path.

The synthesis-prompt MUST therefore describe the *content* of the
synthesis — what to consolidate, what shape, what tone — and MUST
NOT ask the synthesizer to "create a document", "save the answer",
"write to path X" or invoke any pseudo-tool-call syntax. Such
instructions cause the LLM to emit hallucinated tool-call text
inside the markdown body, which the engine then has to filter.

Output paths are NOT configurable. The engine persists head
replies and the final synthesis to
`_zaphod-drafts/<processId>/<head>.md` and
`_zaphod-drafts/<processId>/synthesis.md` respectively; the
recipe does NOT carry an `outputPathTemplate` field.

## When a Council fits

- **Decisions with competing concerns**: ship a refactor /
  approve a migration / pick a database — concerns trade off
  against each other and a single reviewer would over-optimise
  one axis.
- **Design reviews where blind spots matter**: a single reviewer
  optimises for what they care about; a council surfaces axes
  they wouldn't have considered.
- **Reusable advisory panels**: name a council ("rat-der-macher"),
  consult it with different questions over its lifetime.

## When a Council does NOT fit

- **Pure information retrieval**: "summarise this paper" — one
  head suffices; the others duplicate the work.
- **Sequential workflows**: "outline → chapters → consolidate"
  is a pipeline (use Vogon strategy), not a council.
- **Open exploration without a decision**: synthesis needs a
  resolvable question; without one the synthesis turn is hollow.
