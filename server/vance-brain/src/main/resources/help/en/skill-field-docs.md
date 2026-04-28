# Skill field reference

A **skill** is a named, reusable extension a process can attach to its
system prompt at runtime: a markdown body that adds role-specific
guidance, an optional bundle of reference documents, an additive tool
list, and auto-trigger rules so the skill activates without manual
selection. Skills live at four scopes (most-specific wins): **user**,
**project**, **tenant**, **bundled**.

## Identity

### `name`
*string, immutable, required* — kebab-case identifier, unique within a
scope/owner. Set when the skill is created and not editable
afterwards. Use a different name to replace; the old override survives
until you delete it.

### `title`
*string, required* — short human label shown in selectors and the
admin UI.

### `description`
*string, required* — one-line summary of when this skill should be
used. Surfaces in the picker, the trigger-explanation, and the
hand-over message a worker process sees.

### `version`
*string, required, default `1.0.0`* — manually-vended semver. Bump on
breaking changes so callers can pin to a known-good version (forward
compatibility is on the editor; the server doesn't enforce semver
semantics).

### `enabled`
*boolean, default `true`* — disabled skills are skipped during
cascade resolution (treated as if they didn't exist). Useful for
temporarily silencing a skill without deleting its content.

## Trigger rules

### `triggers`
*list of trigger objects, optional* — auto-activation rules. If empty,
the skill only activates by explicit selection. Multiple triggers are
OR-combined: any matching trigger fires the skill.

Each trigger has:

- **`type`**: `PATTERN` or `KEYWORDS`.
- **`pattern`**: Java regex string (only for `PATTERN`).
- **`keywords`**: list of strings (only for `KEYWORDS`). The trigger
  fires when ≥ 50 % of the listed keywords appear in the input
  (case-insensitive substring match).

Examples:
- `{ type: PATTERN, pattern: "schau.*(diff|PR)" }` — fires on "schau
  dir den PR an".
- `{ type: KEYWORDS, keywords: [review, diff, PR] }` — fires when at
  least 2 of {review, diff, PR} appear.

## Prompt extension

### `promptExtension`
*string (markdown), optional* — appended to the system prompt at
activation time. This is the actual "instructions" the skill carries.
Engine hard-rules stay binding; the skill specialises a role on top.

## Tools

### `tools`
*list of strings, optional* — tool names added to the engine/recipe
allow-list at turn time. Skills are **additive only** — they can grant
extra capabilities but cannot remove engine defaults. Empty list means
"the skill needs no extra tools".

## Reference documents

### `referenceDocs`
*list of doc objects, optional* — markdown documents the skill packs
along with its prompt extension. Useful for checklists, style guides,
glossaries that the model should keep in context.

Each doc has:

- **`title`**: short label for the doc (shown in trace logs and the
  admin UI).
- **`content`**: markdown body.
- **`loadMode`**:
  - `INLINE` — appended to the system prompt at activation.
  - `ON_DEMAND` — *reserved for future use*; today behaves like
    `INLINE`.

## Tags

### `tags`
*list of strings, optional* — free-form discovery tags shown in the
selector and used by future filter UIs. Examples: `code`, `review`,
`research`, `internal-only`.

## Scripts

### `scripts`
*list of script objects, optional* — JavaScript snippets bundled with
the skill. Phase 1 (current) **persists and edits** scripts only; the
runtime that turns them into LLM-callable tools is a later phase
(see `specification/skills.md` §13).

Each script has:

- **`name`**: kebab-case identifier inside the skill (will become part
  of a future tool name like `skill_<skillname>__<name>`).
- **`description`** *(optional)*: one-liner that becomes the tool
  description the LLM sees once Phase 2 mounts scripts as tools.
  Without it the model has only the name to go on when deciding
  whether to call. Recommended: tell the model *what the script does*
  and *what it returns*.
- **`target`**:
  - `BRAIN` — runs server-side in the brain's `JsEngine` (GraalJS
    sandbox). Future host bindings give access to the project
    workspace and other brain tools.
  - `FOOT` — runs client-side in the foot CLI's `ClientJsEngine`. The
    brain routes the script + args through the WebSocket connection;
    foot evaluates and returns the result.
- **`content`**: the JavaScript source.

### Phase 1 caveat — runtime not active yet

In v1 the engine ignores skill-attached scripts. Save what you mean to
ship; the field is editable so the catalog is ready when the
mounting + host-bindings phases land. Rough plan:

| Phase | Status | What it adds |
|---|---|---|
| 1 — schema + editor | done | Scripts persist; UI lets you create/edit/remove them. |
| 2 — Skill-as-Tool | open | When a skill is active, each script is registered as a tool in the engine's tool-loop. |
| 3 — host bindings | open | Inject a `vance.*` global so scripts can reach project workspace, other tools, logging. |
| 4 — FOOT routing | open | WebSocket protocol extension to ship a script body + args to a connected foot client and await the result. |
