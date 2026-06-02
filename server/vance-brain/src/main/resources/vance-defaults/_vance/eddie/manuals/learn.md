---
audience: eddie
triggers: LEARN, learn action, persona, fact, remember user, merk dir, vergiss nicht, ab jetzt redest du, user preference, user style, persona update, append fact, system prompt persona, How to talk to this user, What I know about this user
summary: Eddie's LEARN action — when to save persona-style cues vs. atomic facts about the user, and which mode (replace/append) to pick.
---
# LEARN — saving things about the user

Eddie's `LEARN` action stores facts about the user in your personal
memory. Two scopes — `persona` and `fact` — with different shapes
and different lifecycles. Both feed back into your system prompt
at turn start, so what you save here changes how you read every
future turn with this user.

## When to consult

You're about to emit a `LEARN` action and want to know which scope
fits, what `mode` does, or whether the moment even justifies a
save.

## The two scopes

### `scope: "persona"` — how to talk to this user

A compact summary of tone, style preferences, "talk to me like X"
instructions. Loaded into the system prompt of **every Eddie turn**
as a *How to talk to this user* block. Keep it short — a few
sentences, not a novel — because every turn pays for it.

- Default `mode: "replace"` — clean rewrite of the persona text.
  This is right when the user gives a comprehensive style cue
  ("from now on talk to me like Douglas Adams").
- `mode: "append"` — adds a note without touching the rest. Use
  when the existing persona is mostly right and you just need to
  pin one extra detail.

Example:

```json
{ "type": "LEARN",
  "reason": "User asked for more sarcasm — persona update.",
  "scope": "persona",
  "content": "Sprich mich locker und gerne sarkastisch an, im Stil von Douglas Adams. Knappe, trockene Antworten. Direkter als üblich.",
  "message": "Notiert. Werde ich mir merken." }
```

### `scope: "fact"` — append-only fact about the user

A single fact: birthday, favourite colour, allergies, hobbies, what
they don't like. Stored as an **append-only journal** with date
stamps. Also loaded into the system prompt at turn start as a
*What I know about this user* block.

`mode` is ignored for facts — every save is an append. Keep each
entry as a single, atomic fact so the journal stays scan-readable
across many turns.

Example:

```json
{ "type": "LEARN",
  "reason": "User mentioned their birthday in passing.",
  "scope": "fact",
  "content": "Geburtstag: 15. April",
  "message": "Notiert." }
```

## When to use LEARN

- User says it explicitly: *"merk dir das"*, *"vergiss das nicht"*,
  *"ab jetzt redest du so"*.
- User reveals a preference, an aversion, or a fact you'll
  plausibly need later (allergy, birthday, occupation, location,
  likes/dislikes).
- You notice a repeated style correction ("answer shorter", "be
  more direct") — that's a persona update.

## When NOT to use LEARN

- Conversational throwaways that aren't relevant past the turn
  (*"ich hab grad Hunger"*).
- Things the user already keeps with `scratchpad_set` — the
  scratchpad is **their** notepad; LEARN is **your** model of
  them.
- Without a clear signal. Prefer to ask back — *"soll ich mir das
  merken?"* — over guessing what's worth keeping. False saves
  pollute every future prompt.

## Anti-patterns

- Saving a long persona ("the user is a thoughtful product
  manager who values clear communication and ...") — that block
  ships every turn. Keep persona terse.
- Splitting a single trait across multiple `fact` entries instead
  of a single sentence. Atomic ≠ fragmented.
- Re-running `LEARN scope=persona` with `mode=replace` to add one
  more bullet — use `append` for incremental edits.
