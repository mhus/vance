---
triggers: decide, decision, classify, classification, route, routing, yes/no, gate, rate, rating, score, probability, confidence, threshold, Entscheidung, entscheiden, klassifizieren, einordnen, bewerten, zem_decide, vance.zem, Zem, System One, Jev, nimble, decision model
summary: How to decide, classify, route or rate with a decision model (System One) via the zem_decide tool or vance.zem in scripts — question types, state shaping, thresholds, profiles, limits and anti-patterns.
---
# Decisions — Zem

A **decision model** (System One: Jev, nimble, …) does not write text.
It reads a **state** and answers **typed questions** with calibrated
probabilities — in ~0.1–0.7 s, for a fraction of an LLM call. Use it
whenever the answer is a choice, not prose.

## Question types

| type | ask | answer |
|---|---|---|
| `noul` | a yes/no condition | `noul` = p(yes) |
| `choice` | exactly one of 2+ options | `choice` + `p` (distribution) |
| `score` | a position on 2–10 ordered levels, lowest first | `score` = expected level (may fall between two) + `p` |

Every answer carries `confidence` (0..1). Questions are answered
**independently** — ask several at once, it costs almost nothing extra.
"Several options apply" is not a choice: ask one `noul` per option.

## Tool: `zem_decide`

```json
{"state": {"message": "Charged twice for order 4711", "channel": "mail"},
 "questions": [
   {"name": "intent", "type": "choice", "instructions": "Which intent does `message` express?",
    "options": {"refund": "wants money back", "duplicate_charge": "charged more than once",
                "other": "anything else"}},
   {"name": "urgent", "type": "noul", "instructions": "Does `message` need action today?",
    "yes": "a deadline, an outage or an angry customer", "no": "can wait"},
   {"name": "anger", "type": "score", "instructions": "How angry is the customer?",
    "levels": ["calm", "annoyed", "furious"]}]}
```

Answer: `{"model": "…", "answers": {"intent": {"type": "choice", "choice":
"duplicate_charge", "confidence": 0.91, "p": {…}}, "urgent": {"type": "noul",
"noul": 0.12, "confidence": 0.76}, …}, "latencyMs": 420}`. On failure:
`{"error": "<reason>", "message": "…", "retryable": …}`.

Plain text instead of an object: `"text": "…"`. A saved profile instead
of inline questions: `"profile": "<name>"` (inline questions with the same
name replace the profile's).

## In scripts: `vance.zem`

```js
const d = vance.zem.decide("command-safety", {command: name, args: args});
if (d.unsafe.noul >= 0.5) vance.guard.deny("unsafe (p=" + d.unsafe.noul + ")");
// inline questions (System-One shape: criteria instead of options/levels/yes/no):
vance.zem.decide(null, {message: text}, [{name: "spam", type: "noul", instructions: "Is `message` spam?"}]);
```

A failure throws; the message starts with the reason, e.g. `[rate_limited]`.
A **gate must treat a throw as deny** (fail-closed).

## Shaping the state

- An **object with meaningful field names**; refer to fields in the
  question with backticks (`` `customer.tier` ``).
- **Only relevant fields.** Noise lowers accuracy measurably. Profiles
  can project the state with `stateSelect: [message, customer.tier]`.
- The state may be in any language; questions and option descriptions
  stay English.

## Thresholds

Compare probabilities against thresholds in code, e.g. noul: yes ≥ 0.8,
no ≤ 0.2, anything between → ask a human or an LLM. **Thresholds do not
carry over between models** (one model's 0.92 is another's 1.00) — a
profile that relies on a threshold pins a concrete model
(`openrouter:typesafe/jev-1.13`), not an alias.

## Profiles

A profile is a recipe with `engine: zem` (usually `internal: true`):
`params.model`, `params.questions` (System-One shape, `criteria`),
optional `params.stateSelect`. Bundled example: `command-safety`.

## Limits (per model, checked before the call)

Choice options: Jev 255, local nimble **26**. Structured option
descriptions (objects): Jev yes, nimble no. Score levels: 2–10.
Option keys and question names: no `.`. A profile within nimble's limits
runs everywhere. Questions above a model's per-call maximum are split
automatically.

`invalid_choice` usually means no decision model is configured for
`default:decision` — tell the user to set it in the LLM setup form; do
not retry.

## Do not

- **No arithmetic, counting or date comparison** — decision models get
  them wrong. Compute in code, put the result into the state
  (`"daysLate": 3`), then ask.
- **No prose, no extraction, no explanations** — that is `vance.llm` /
  an LLM worker.
- **No sole gate over untrusted text** — injected text can flip a
  decision. Combine with other checks.
- **Option order matters a little** — the first option is slightly
  favoured; put `other` last, do not stack the likely answer first.

## See also

- `manual_read('vance-script-llm')` — when you need text, not a decision.
- `manual_read('completion-guards')` — guard scripts that gate on a verdict.
