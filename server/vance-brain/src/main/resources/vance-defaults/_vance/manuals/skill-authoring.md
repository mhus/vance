---
triggers: write a skill, create a skill, author a skill, SKILL.md, skill frontmatter, lifecycle turn, lifecycle sticky, lifecycle shot, prompt macro, slash command with arguments, skill arguments, args.text, skill triggers, /skill, one-shot skill, run a skill in a separate agent, skill without chat history, spawn a worker for a skill, Skill schreiben, Skill anlegen, Skill mit Argumenten, eigenen Befehl anlegen
summary: How to write a skill — the SKILL.md frontmatter, the three lifecycles (turn = one task, the default / sticky mode / shot prompt-macro), running a skill in a fresh worker via run.target: spawn, invocation arguments via arguments: + {{ args }}, and which of body / action:/ activate: fires when — and from which surface (user /skill vs agent skill_activate + skill_fire).
---
# Writing a skill

A **skill** is a reusable capability bundle: a `SKILL.md` with YAML
frontmatter plus a markdown body. Create it as a document at
`_vance/skills/<name>/SKILL.md` — the folder name *is* the skill name.
Cascade is `_user_<login>` → project → `_tenant` → bundled; the innermost
layer wins, so a user copy overrides the project's.

## Decide the shape first

| You want | Use |
|---|---|
| **Instructions for one task** ("write a report like this", "debug like this") | `lifecycle: turn` (the default — no field needed) — active for the one turn that does the task, then it clears itself |
| A **mode** the model stays in ("review mode", "a house style") | `lifecycle: sticky` — body goes into the system prompt on every turn until `/skill clear` |
| A **command** that runs once and leaves nothing behind | `lifecycle: shot` — the body is the prompt that gets fired, the skill never registers |
| A **configuration macro** (install a guard, set a mode) | `lifecycle: shot` with `activate:` commands and an empty body |
| A standing skill just once | any lifecycle, invoked as `/skill <name> --once`; a task skill kept anyway: `/skill <name> --sticky` |
| Work that must **not** see this conversation (review, second opinion) | `run.target: spawn` — runs in a fresh worker without the chat history (see below) |

`shot` and `--once` are different things, easy to mix up:

- **`shot`** never enters the active-skill list. It has no system-prompt
  presence at all, contributes **no** `tools:`, and has no `deactivate:`
  — there is nothing to clean up. Its body fires as one turn.
- **`--once`** *is* a sticky activation, alive for exactly one turn: body
  in the system prompt, `tools:` granted, auto-cleared afterwards.

## Minimal prompt-macro (the common case)

```yaml
---
title: Code Review
description: Review the current changes
version: 1.0.0
tags: [code, coding]
lifecycle: shot
arguments: true
---
Review the current code changes now. Gather the diff first
(git status / git diff, or read the changed files), then report bugs
with a concrete failure scenario. Skip style nits.
{% if args.text %}Focus on: {{ args.text }}{% endif %}
```

`/skill code-review src/main/java` → the body renders with
`args.text = "src/main/java"`, fires one turn, and the skill is gone.

Note for the agent surface: `skill_activate` on a shot skill only runs its
`activate:` commands — the body fires when the agent calls `skill_fire`.

## Arguments

`/skill <name> <rest of the line>` hands the trailing text to the skill.
**Whether the skill consumes it is decided by `arguments:`** — and exactly
one side gets the text, never both:

| `arguments:` | What happens with the trailing text |
|---|---|
| absent | Not bound. It arrives as a **plain user message** in the same turn — useful, just not placed inside your prompt |
| `true` | Bound raw: `{{ args.text }}` (whole rest) and `{{ args.words }}` (token list) |
| a list of declarations | Bound raw **and** by name: `{{ args.scope }}` |

```yaml
arguments:
  - name: scope
    type: string          # string | number | integer | boolean | object | array
    description: What to review — a path, a git range, or empty for the working tree.
    required: false
  - name: depth
    type: integer
```

Binding is **positional**, shell-style: declared arguments take one token
each, and the **last** `string` argument swallows the remainder. A missing
`required` argument fails the activation with a clear message instead of
rendering an empty hole. Missing optional arguments render empty.
`key=value` parsing does not exist — use positions, or read
`{{ args.text }}` and split it yourself.

Values are always data in the render context. Never build a template out
of them.

## The three things a skill can do

| Ingredient | When it fires | Where it lands |
|---|---|---|
| **body** | the one turn of a `turn` skill, every turn while `sticky` — or **once** as the turn-prompt when `lifecycle: shot`. Activated by the agent mid-turn (`skill_activate`), it comes back as the tool result instead, effective right away | system prompt / tool result / user turn |
| **`action:`** | once, on a fresh activation from the **user surface** (`/skill`) or on the **worker side** of a spawn skill; never from the agent or the auto-trigger — the agent fires it explicitly via `skill_fire` after `skill_activate` | fires one LLM turn |
| **`activate:` / `deactivate:`** | once, on activation / on clear | engine commands, no model, no tokens |

Order on activation: `activate:` commands first (they set state), then the
turn-prompt (it observes that state).

`action:` wins over the body as the turn-prompt. Use both together when
the body is long methodology and the action is a short kick-off:

```yaml
lifecycle: sticky
action: |
  Review the current changes now, then report as instructed.
---
# 60 lines of review methodology…
```

## Triggers

```yaml
triggers:
  - type: KEYWORDS
    keywords: [code review, review my changes]
  - type: PATTERN
    pattern: "review.*(diff|changes)"
```

KEYWORDS fires when any one keyword appears in the message
(case-insensitive substring); PATTERN is a case-insensitive regex `find()`. Keep keywords in the languages your
users type — trigger matching is language-dependent, unlike prompts.

An auto-triggered skill activates **one-shot** for the running turn, and
its turn-prompt is deliberately **not** fired (a turn is already in
flight). The same is true for the agent path: `skill_activate` never
fires a turn-prompt — the agent schedules one explicitly with
`skill_fire`. Consequence: a `lifecycle: shot` skill whose only effect
is a body does nothing on the trigger path *and* nothing on a bare agent
activation — give it `activate:` commands, make it a `turn` or `sticky`
skill, or expect explicit invocation only.

## How long it lives (`lifecycle:`)

Most skills are instructions for one task: write a report, debug a
failure, review a change. Leave `lifecycle` out — the default `turn`
keeps the skill for exactly the turn that does the task and then clears
it, so nothing lingers in the context. Activated before the turn (word
trigger, `/skill`, the panel) the body sits in that turn's system
prompt; activated by the model mid-turn (`skill_activate`) the
instructions come back as the tool result and apply right away.

Declare `lifecycle: sticky` only for a standing mode that must hold over
many turns — a house style, a design blueprint a design grows under.
Recipe `defaultActiveSkills` are always sticky, whatever you declare.

## When the work is delegated (`delegation:`)

When a process hands work to a worker (`process_spawn`, Arthur's
`DELEGATE`), its active skills go along by default (`delegation:
carry`) — whoever does the task needs your instructions. Declare
`delegation: stay` when the skill is about the conversation itself
(helping the user decide, a chat persona), not about the work. A model
can also hand skills over explicitly: `process_spawn(skills=[…])`.

## Activation level (`activation:`)

How the skill is offered **where it is relevant** — one ladder, one
frontmatter field. Relevance is the recipe's call: its `relevantSkills`
selects skills by name or `@label` (your `tags:`), and a skill it does
not select is `hidden` on that recipe's processes, whatever you declare.
You decide the level; the recipe decides where you make sense.

| Level | Skill panel | Word trigger | Listed under "Available skills" for the model |
|---|---|---|---|
| `hidden` | collapsed (a toggle shows it) | off | no |
| `manual` | shown | off | no |
| `trigger` | shown | armed (your `triggers:` block) | no |
| `agent` | shown | **off** — the model decides | **yes** |

No level is a permission: the user (`/skill`), the agent
(`skill_activate` / `skill_fire`) and recipe defaults can activate any
enabled skill on every level, `hidden` included; only `enabled: false`
and the recipe's `allowedSkills` whitelist block activation.

When the field is absent it derives from `triggers:`: present →
`trigger`, absent → `manual`. Pick:

- `agent` — the model should pick you up by itself when the work fits
  (the bundled coding skills, `design-blueprint`). Your description is
  all it sees, so write it as "use when …". Triggers are not armed on
  this level.
- `trigger` — a deterministic word match is the right signal.
- `manual` — a deliberate tool the user reaches for (a macro like
  `commit-message`).
- `hidden` — experimental or internal; reachable only on purpose.

Recipes select by the **domain labels** in your `tags:` — tag by what
you contribute to, not by activity: `coding`, `design`, `decision`,
`writing`, `research`. Chat recipes exclude the coding group
(`relevantSkills: ["!@coding"]`), coding workers select it plus the design group
(`["@coding", "@design"]`), recipes without the field consider everything relevant.

Users can override the mode per scope without forking your SKILL.md —
`_vance/config/skill_activation.yaml` (`skills: { <name>: hidden }`) in
the tenant, the project or their own user project. The three documents
merge **per entry**: for each skill the innermost entry wins (user →
project → tenant), and a skill no document mentions keeps your
declaration. Overrides win over your declaration. An override changes the
level, never the recipe's relevance.

Keep listed skills honest: the list shows only the name and the
description (the title when there is none), so write the description as
"use when …". A skill that is already active drops out of the list.

## Running in a fresh worker (`run.target: spawn`)

A skill acts in the calling process by default. Sometimes that is exactly
wrong: a code review should judge the code, not the conversation that
produced it — inheriting the chat history biases the verdict, costs
context, and mixes up the roles.

```yaml
run:
  target: spawn          # inline (default) | spawn
  recipe: code-read      # required — the worker's engine lane
  inherit: none          # default for spawn
action: |
  Review the current code changes now. Gather the diff first, then report.
```

The caller keeps **nothing**: no active skill, no injected message,
nothing to clear. A fresh worker is spawned instead, the skill is sticky
*there* (body = its system prompt, `tools:` granted, `args` bound on every
turn), and `action:` is its first message. When it finishes, its last
answer comes back to the caller as a process event, so write `action:`
such that the worker's final message is the whole result.

Rules that bite:

- **`action:` is mandatory** here. The body is the worker's system prompt,
  not its task — without `action:` it would start and idle.
- **Not combinable with `lifecycle: shot`** — shot means "registers
  nowhere", spawn means "registers sticky in the child".
- **Triggers never spawn.** Only the explicit paths do — `/skill <name>`
  from the user, `skill_activate` from the agent. Guard scripts don't
  spawn either.
- **Same session**, so project memory still applies — only the chat
  history is left behind. That is usually what you want.

## Templating

The body, `action:`, and `activate:` strings are Pebble templates. Beyond
`args`, a sticky body sees `tier`, `model`, `provider`, `mode`, `profile`,
`recipe`, `engine`, `lang`, `params` — so model variants live *inside* one
skill:

```
{% if tier == "small" %}Keep it to three findings.{% else %}Be thorough.{% endif %}
```

A `shot` turn-prompt is rendered at activation time, when no model is
resolved yet: `tier` / `model` / `provider` are empty there. Put
tier-dependent wording in a sticky body instead.

Reference-doc content is **not** templated — those are data files, and
literal `{% %}` in them must survive.

## Other frontmatter worth knowing

| Field | Effect |
|---|---|
| `tools` | Tool names added to the whitelist while active (add-only, never removes). **Ignored for `shot`** — it never registers |
| `manualPaths` | Folders the skill contributes to `manual_read` / `manual_list` while active |
| `referenceDocs` | Sibling files, `loadMode: INLINE` (into the prompt) or `ON_DEMAND` (listed for `manual_read`) |
| `enabled: false` | Hides the skill — including a same-named one from an outer cascade layer |
| `tags` | Discovery hints **plus group membership**: each tag is a selectable `@label` — a recipe saying `relevantSkills: ["@coding"]` considers every skill tagged `coding` relevant, `["!@coding"]` hides them. Relevance never activates anything; your `activation:` level decides how a relevant skill is offered. Tag by domain (`coding`, `design`, `decision`, `writing`, `research`), not by activity. Tags have no other effect: trigger, activation and tool logic never read them |
| `activation` | Who may activate: `manual` (user-only, agent refused) / `trigger` (word activation armed) / `agent` (advertised to the LLM). Absent → derived from `triggers:` (see above) |

`title`, `description`, `version` are required. `description` doubles as
what the model and the skill picker see, so write it as "use when …".

## Checking your work

- `/skill list` — is it visible in this scope, and from which layer?
- `/skill <name>` — activate and watch: did the turn fire, are the
  arguments where you put them?
- `/skill clear` — sticky only; a `shot` skill was never active.
