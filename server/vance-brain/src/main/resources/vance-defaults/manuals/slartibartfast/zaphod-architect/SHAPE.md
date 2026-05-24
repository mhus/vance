# Zaphod Council Recipe — Shape

This manual describes what a Zaphod Council Recipe is structurally.
Slartibartfast's GATHERING ingests it as engine-bundled evidence
so DECOMPOSING can tie subgoals to concrete claims even when no
project-specific kit is installed.

## What is a Zaphod Council

A Zaphod Council Recipe drives **N parallel head sub-processes**
against the same input, then runs a **synthesizer turn** that
combines their outputs into one consolidated answer. The council
is a *reusable asset* — the same recipe is consulted repeatedly
with different questions over its lifetime.

## Mandatory recipe fields

A council recipe MUST declare:

- `name`: kebab-case identifier, unique within the project namespace.
- `engine: zaphod`
- `params.pattern: COUNCIL` (V1 only supports COUNCIL; debate /
  generator-critic / branch-and-vote are V2)
- `params.heads`: a list of head specifications, 2 to 5 entries.
- `params.synthesisPrompt`: instructions for the synthesizer turn.

## Per-head fields

Each entry in `params.heads` MUST carry:

- `name`: unique kebab-case role identifier (e.g.
  `security-reviewer`, `cost-optimist`).
- `recipe`: name of an existing project recipe — typically `ford`
  or a ford-style conversational variant. The recipe defines the
  head's execution shape; persona steers behaviour.
- `persona`: 1-3 sentences describing the head's perspective,
  bias, or expert role. Personae MUST be *distinct* across heads —
  generic descriptions like "a thoughtful assistant" collapse the
  council to one answer.

## Heads-count rules

- 2 heads minimum (below that there is nothing to synthesise).
- 3-5 heads is the sweet spot (balanced breadth, manageable
  synthesis).
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
NOT have access to file-writing tools like `doc_create_text`,
`doc_write_text` or `doc_create_kind`. The engine itself parses
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
