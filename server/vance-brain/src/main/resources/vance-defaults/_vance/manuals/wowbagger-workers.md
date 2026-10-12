---
triggers: wowbagger worker, workerRecipe, workerScript, worker script, Zem worker, decision worker, script worker, maxCallsPerRecord, mergeIntoRecord, //wowbagger worker, Worker wechseln, Worker umstellen
summary: The three Wowbagger worker types (LightLlm, Zem, script) — when to use which, the Zem profile shape, the worker-script contract, the per-call model gate and the call budget.
---
# Wowbagger workers

A worker turns one chunk of records into exactly one output line per
record. The pool does everything else (claim, commit, retry, merge).
Workers are light — a call per chunk, never a spawned process; a
recipe for a chat or strategy engine is refused.

| Worker | Set with | Unit | Uses `task` | Model gate |
|---|---|---|---|---|
| LightLlm | `workerRecipe` = profile with `internal: true` (default `wowbagger-worker`) | one LLM call per chunk | yes | profile model, checked before the run |
| Zem | `workerRecipe` = profile with `engine: zem` | one decision per record | no — the profile's questions are the task | decision model, checked before the run |
| Script | `workerScript` = JS file in the run root (or a profile `engine: hactar`, `internal: true`, `params.script`) | one script run per chunk | available as `vance.params.task` | **every** `vance.llm`/`vance.zem` call, at runtime |

**Rule of thumb:** only decisions → Zem; one free-form LLM step → LightLlm;
several steps or a mix → script.

## Zem worker

Write a Zem profile (`manual_read('zem')` for the question types) and set
it as `workerRecipe`. With `inputFormat: jsonl` each record object is the
decision state (`stateSelect` in the profile trims it to the relevant
fields). Output per record: the answers keyed by question name,
e.g. `{"intent":{"type":"choice","choice":"refund","confidence":0.93}}`.
`mergeIntoRecord: true` writes the record back with the answers under
`_zem` — convenient for filtering the result afterwards.

## Script worker

Write the script into the run root, then `wowbagger_configure workerScript:
"triage.js"`. Contract:

```js
// in:  vance.params.records — objects (jsonl) or strings (lines)
//      vance.params.task, vance.params.chunk.{index,startRecord}
// out: the script's LAST EXPRESSION — a list with one result per record,
//      in order (strings = the line as-is, objects = one JSON line).
//      No top-level `return` (a syntax error in scripts).
vance.params.records.map(rec => {
  const d = vance.zem.decide("ticket-triage", rec);
  if (d.intent.confidence >= 0.8) return {...rec, intent: d.intent.choice};
  return {...rec, ...vance.llm.callForJson("ticket-deep", JSON.stringify(rec))};
});
```

- Every `vance.llm` and `vance.zem` call must hit a model on
  `wowbagger.allowed-models` — a refused call fails the chunk and names the
  model. The user approves models; you cannot.
- `maxCallsPerRecord` (default 3) caps provider calls per chunk at
  N × records — raise it only when the script genuinely needs more steps.
- Tools: the script sees your tool surface. A tool call runs once per
  record — think twice before writing files or calling external services
  from a worker script.
- Test on a canary: start with one chunk (`chunkSize` small, 1 thread),
  read the published chunk doc, then scale.

## Switching the worker

`wowbagger_configure` (pool stopped) or, for the user, `//wowbagger worker
<recipe> | --script <path> | default [--check]`. A switch mid-run is
refused; to redo the whole source with the new worker, start with
`force: true`.
