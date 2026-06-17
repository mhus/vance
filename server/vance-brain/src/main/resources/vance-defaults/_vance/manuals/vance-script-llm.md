---
triggers: vance.llm, vance.llm.call, vance.llm.callForJson, LightLlmService, single-shot LLM, classification, klassifikation, mini-llm, ohne spawn, sync llm, schema validation, jeltz, internal recipe
summary: How to make synchronous one-shot LLM calls from a script via vance.llm.call / vance.llm.callForJson. No process spawn, no lane lock; backed by LightLlmService with a Recipe-as-config profile.
---
# Synchronous LLM calls from a script — `vance.llm.*`

Use this when you need an LLM verdict *during* a script run — a
classification, title-generation, summary, schema-validated extraction
— and you do **not** want to spawn a whole think-process for it.

Two methods, both synchronous, both return inline:

```js
// Free-text reply, caller post-processes
const text = vance.llm.call(recipeName, userPrompt, pebbleVars?);

// JSON-validated reply (Jeltz-style retry-loop), returns parsed Map
const obj = vance.llm.callForJson(recipeName, userPrompt, pebbleVars?);
```

## Recipe must be `internal: true`

```yaml
description: |
  Classifies one mail as important/unimportant.
engine: jeltz                    # schema-loop engine for JSON output
internal: true                   # REQUIRED — non-internal recipes are rejected
params:
  model: default:fast
  maxAttempts: 2
  temperature: 0.1
promptPrefix: |
  ## Rules
  {{ rules }}

  ## Mail
  From: {{ from }}
  Subject: {{ subject }}

  {{ body }}

  Reply with JSON: { "important": <bool>, "summary": "..." }
tags:
  - internal
```

`LightLlmService` rejects any recipe that isn't marked `internal:
true` — this guard keeps spawnable worker-recipes (Arthur, Eddie,
Marvin, ...) cleanly separated from config-profile recipes used
synchronously by scripts and services.

Recipes for `vance.llm` live under `_vance/recipes/<name>.yaml`
(NOT `documents/_vance/...` — `_vance` is its own root folder).

## Three arguments

| Position | Type | Purpose |
|---|---|---|
| `recipeName` | string | Name (filename without `.yaml`). Must exist + be `internal: true`. |
| `userPrompt` | string | The user message — appended after the recipe's rendered system prompt. Required. Often a short trigger like `"Classify."`; main content goes via `pebbleVars`. |
| `pebbleVars` | object (opt.) | Variables for the recipe's Pebble template. Available as `{{ key }}` in `promptPrefix`. |

Scope (tenantId, projectId, processId) is auto-bound from the
script's `vance.context.*` — you cannot escape it.

## When to use `callForJson` vs. `call`

- **`callForJson`** — when the recipe ends with "reply with JSON of
  this shape". The service parses the response and re-asks the LLM
  on parse/schema failure, up to the recipe's `maxAttempts`. Returns
  a `Map<String, Object>`.
- **`call`** — when you just want raw text (a title, a slug, a
  one-line summary, a free-text label). You post-process yourself.

Faustregel: any time the recipe asks the LLM for structured data
(`{"important": ...}`, `{"category": "X", "score": 0.7}`, ...),
use `callForJson` — even if the JSON is small. The retry loop costs
nothing on success and saves you from manual parse-error handling.

## Concrete: a one-mail classifier

Script side:

```js
const verdict = vance.llm.callForJson('mail-rate', 'Bewerte die Mail.', {
  rules: vance.documents.read('documents/mail-rules.md'),
  from: full.from,
  subject: full.subject,
  body: full.body
});

if (verdict.important === true) {
  vance.tools.call('inbox_post', { /* ... */ });
}
```

Recipe (`_vance/recipes/mail-rate.yaml`): see the YAML at the top
of this manual.

## Errors

Failures surface as plain JS `Error`s — catch them:

```js
try {
  const v = vance.llm.callForJson('mail-rate', 'Classify.', vars);
} catch (e) {
  vance.log.warn('llm failed', { msg: e.message });
}
```

Common messages:

| Error | Cause |
|---|---|
| `recipe 'X' is not marked internal:true` | Recipe exists but missing `internal: true`. Add the flag and save. |
| `recipe 'X' not found` | Wrong recipe name, or recipe-cache stale (Brain-restart helps). |
| `schema validation exhausted` | LLM refused to produce valid JSON within `maxAttempts`. Tighten the prompt, or raise `maxAttempts` in the recipe. |
| `vance.llm requires a tenant-scoped run` | Script was launched without a tenant in scope (trigger-scoped sandbox). Run from a project context. |

## When NOT to use `vance.llm`

- **You want a Worker that uses tools / does multi-turn** — spawn a
  process via `vance.process.spawn(...)` with a worker recipe (Arthur,
  Eddie, Marvin, ...). `vance.llm` is single-shot, no tools.
- **You want a Chat that continues a conversation** — same, spawn an
  Arthur. `vance.llm` is stateless per call.
- **You want streaming output** — `vance.llm` returns when the LLM
  finished. Use a real engine for live streaming UX.

`vance.llm` is the "small-LLM-helper" surface — for the moments
where you'd otherwise have spawned a whole engine just to ask one
yes/no question. Use it generously where it fits; don't stretch it
into worker territory.

## See also

- `manual_read('scripts')` — generelle JS-Script-Surface (`vance.tools`,
  `vance.context`, `vance.log`, `vance.process`, `vance.documents`).
- `manual_read('inbox-post')` — wie man eine LLM-Klassifikation als
  Inbox-Item an den User dropt.
- Spec: `specification/light-llm-service.md` — Recipe-Schema,
  Pebble-Render-Kontext, Schema-Loop-Mechanik.
