---
triggers: write a skill, create a skill, author a skill, SKILL.md, skill frontmatter, lifecycle sticky, lifecycle shot, prompt macro, slash command with arguments, skill arguments, args.text, skill triggers, /skill, one-shot skill, run a skill in a separate agent, skill without chat history, spawn a worker for a skill, Skill schreiben, Skill anlegen, Skill mit Argumenten, eigenen Befehl anlegen
summary: How to write a skill — the SKILL.md frontmatter, the three lifecycles (sticky mode / one-shot / shot prompt-macro), running a skill in a fresh worker via run.target: spawn, invocation arguments via arguments: + {{ args }}, and which of body / action:/ activate: fires when.
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
| A **mode** the model stays in ("review mode", "a house style") | `lifecycle: sticky` (default) — body goes into the system prompt on every turn until `/skill clear` |
| A **command** that runs once and leaves nothing behind | `lifecycle: shot` — the body is the prompt that gets fired, the skill never registers |
| A **configuration macro** (install a guard, set a mode) | `lifecycle: shot` with `activate:` commands and an empty body |
| A mode for exactly one turn | `lifecycle: sticky`, invoked as `/skill <name> --once` |
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
tags: [code, review]
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
| **body** | every turn while sticky — or **once** as the turn-prompt when `lifecycle: shot` | system prompt / user turn |
| **`action:`** | once, on a fresh explicit activation | fires one LLM turn |
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

KEYWORDS fires when ≥50% of the keywords appear as whole tokens; PATTERN
is a case-insensitive regex `find()`. Keep keywords in the languages your
users type — trigger matching is language-dependent, unlike prompts.

An auto-triggered skill activates **one-shot** for the running turn, and
its turn-prompt is deliberately **not** fired (a turn is already in
flight). Consequence: a `lifecycle: shot` skill whose only effect is a
body does nothing on the trigger path — give it `activate:` commands, make
it `sticky`, or expect explicit invocation only.

## Running in a fresh worker (`run.target: spawn`)

A skill acts in the calling process by default. Sometimes that is exactly
wrong: a code review should judge the code, not the conversation that
produced it — inheriting the chat history biases the verdict, costs
context, and mixes up the roles.

```yaml
run:
  target: spawn          # inline (default) | spawn
  recipe: code-review    # required — the worker's engine lane
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
- **Triggers never spawn.** Only explicit `/skill <name>` does.
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
| `tags` | Discovery hints |

`title`, `description`, `version` are required. `description` doubles as
what the model and the skill picker see, so write it as "use when …".

## Checking your work

- `/skill list` — is it visible in this scope, and from which layer?
- `/skill <name>` — activate and watch: did the turn fire, are the
  arguments where you put them?
- `/skill clear` — sticky only; a `shot` skill was never active.
