---
triggers: ai-model pricing eintragen, neues llm modell katalog, modell hinzufügen vance, ai model catalog override, pricing für modell, kontextfenster modell setzen, modell-doc schreiben, ai-models yaml, model catalog manual, modell konfigurieren brain, fim modell konfigurieren, fill in the middle modell, completion modell vance, fim template modell
summary: How to author a MANUAL-layer AI-model metadata doc (pricing, context window, capabilities, FIM template) at `_vance/model/<provider>/<slug>.yaml`. Use when the user wants to register, override, price, or enable Fill-In-the-Middle completion for a model that auto-discovery cannot fill in. Do NOT write to `_vance/model-auto/**` — that is automation-owned.
---
# Writing manual AI-model catalog docs

The brain's `ModelCatalog` reads three layers per (tenant, project) scope
and merges them per-field at lookup time:

```
bundled (classpath)
  ↓ overridden by
_vance system tenant docs
  ↓ overridden by
<tenant> / _tenant project docs
  ↓ overridden by
<tenant> / <project> docs
```

Within each scope there are **two physical paths**:

| Path prefix | Owner | Purpose |
|---|---|---|
| `_vance/model/<provider>/...` | **Operator / Maintainer (manual)** | Pricing, capabilities, custom context-window overrides — survives auto-discovery |
| `_vance/model-auto/<provider>/...` | **Automated discovery** | Provider-listing existence + any metadata the upstream API returns. Overwritten on every discover run. |

Manual beats auto at the same scope. **Always write to `_vance/model/`** when
the user wants pricing, custom capabilities, or any field that should
survive the next discovery pass.

## When to use this manual

- "Please enter pricing for claude-sonnet-4-7"
- "Vance doesn't know the new model X — add it"
- "Override context window for gpt-5 to 1M tokens"
- "Mark deepseek-v4 as a reasoning model"
- "Enable cursor completions with the local Qwen-Coder model (FIM)"
- After the user has run discovery and wants to enrich the auto-docs

If the user just wants the discovery job to run, use the
`POST /brain/{tenant}/admin/ai-models/discover` endpoint or the
"Discover AI Models" button in Profile → Actions — **don't write
manual docs as a substitute for discovery.**

## Path convention

```
_vance/model/<providerInstance>/<slug>.yaml
```

- `providerInstance`: a provider directory name. Must match `[a-z0-9._-]+`.
  Default name equals the wire protocol (`anthropic`, `openai`, `gemini`,
  `ollama`, `lmstudio`, `ollama-cloud`). Tenants can declare extra
  instances via `ai.provider.<name>.type` settings (e.g. `deepseek-direct`
  on the openai wire).
- `slug`: a filesystem-safe filename body. Must match `[A-Za-z0-9._-]+`.
  Sub-directories under the provider dir are allowed and become part of
  the wire name (HF-style: `lmstudio/mlx-community/Qwen3.6-35B.yaml` →
  wire name `mlx-community/Qwen3.6-35B`).
- `.yaml` extension is mandatory.

### Wire-name encoding for `:`-style names

Ollama-style tags like `qwen3:30b` cannot live directly in a filename.
Use a safe slug + the explicit `wireName:` YAML field:

```yaml
# _vance/model/ollama/qwen3-30b.yaml
wireName: "qwen3:30b"
contextWindowTokens: 131072
size: LARGE
stripThinkTags: true
```

The `wireName` field overrides the path-derived name; without it the
catalog assumes the slug *is* the wire name.

## YAML schema

```yaml
# All fields except contextWindowTokens are optional.
wireName: "claude-sonnet-4-5"          # only when filename slug differs
contextWindowTokens: 200000             # combined input+output budget
defaultMaxOutputTokens: 8192            # cap on a single response
size: LARGE                             # SMALL | LARGE (drives recipe-tier choice)
kind: chat                              # chat | image (defaults to chat)
capabilities:                           # optional input modalities
  - vision                              # accepts image blocks
  - pdf                                 # accepts PDF blocks natively
  - thinking                            # honours an explicit reasoning effort
stripThinkTags: false                   # strip <think>…</think> from output text
timeoutSeconds: 60                      # per-call HTTP timeout
actionLoopCorrections: 2                # structured-action loop pacemaker
outputTokenParam: max_tokens            # OpenAI-wire output cap field, see below
unsupportedParams: []                   # sampling knobs the model rejects, see below
reasoningEffortWhenOff: null            # explicit "no reasoning" wire value, see below
fimTemplate: null                       # Fill-In-the-Middle prompt shape, see below

pricing:                                # USD/EUR/… per 1M tokens
  currency: USD
  inputPerMTok: 3.00
  outputPerMTok: 15.00
  cacheReadPerMTok: 0.30                # only when the provider supports prompt cache
  cacheWritePerMTok: 3.75

discoveredBy: manual                    # ALWAYS this — marker that an operator wrote it
discoveredAt: "2026-06-27T10:00:00Z"    # optional, informational
```

### `outputTokenParam` — which field carries the output cap

Only relevant for models on the **OpenAI wire** (provider instances of
type `openai`, including gateways). Two values:

| Value | Wire field | Use for |
|---|---|---|
| `max_tokens` (default) | `max_tokens` | Everything that isn't an OpenAI reasoning model — gateways (cortecs, LM Studio, Ollama, GLM, DeepSeek) only know this one |
| `max_completion_tokens` | `max_completion_tokens` | OpenAI's reasoning models — o-series and gpt-5 upwards |

Sending the wrong field is a hard HTTP 400 that kills the turn. The
families `gpt-5*`, `o1*`, `o3*`, `o4-mini*` are already covered by a
bundled pattern rule — write the field by hand only for a model outside
those globs (a gateway renaming it to `openai/gpt-5`) or to force
`max_tokens` back on a gateway that proxies gpt-5 on the older dialect.

### `unsupportedParams` — sampling knobs the model rejects

List of `temperature`, `top_p`, `top_k`, `frequency_penalty`,
`presence_penalty`, `seed`, `stop` (the OpenAI field names and the
Vance option names both parse). Anything listed is dropped from the
request before it goes out, so the model runs on its own defaults
instead of answering HTTP 400. Reasoning models refuse most of these,
and since Vance always sends a temperature, one missing entry is enough
to make the model unusable.

An **empty list is a statement** — "this one accepts everything" — and
overrides a family pattern. Omitting the field inherits the pattern.

### `reasoningEffortWhenOff` — how to say "don't reason"

Normally unset: not sending `reasoning_effort` *is* how you ask for no
reasoning. Reasoning-native models (gpt-5.x) reason by default and then
refuse to combine that with function tools, so they need the value
spelled out — `"none"`. Set it only for models that document that
value; sending `none` to a model that doesn't know it is itself a 400.

### `fimTemplate` — the Fill-In-the-Middle prompt shape

Completion-trained models (Qwen-Coder, DeepSeek-Coder, Codestral,
StarCoder) fill a hole between prefix and suffix only when the request
carries their family's FIM control tokens — the families genuinely
differ, so this is per-model metadata, not a global constant:

| Family | Template |
|---|---|
| Qwen2.5/Qwen3-Coder, StarCoder | `<fim_prefix>{prefix}<fim_suffix>{suffix}<fim_middle>` |
| DeepSeek-Coder | `<|fim▁begin|>{prefix}<|fim▁hole|>{suffix}<|fim▁end|>` |
| Codestral | `[PREFIX]{prefix}[SUFFIX]{suffix}[MIDDLE]` |

The markers `{prefix}`/`{suffix}` are spliced with the text
before/after the cursor. Write the field by hand only outside the
bundled patterns (`qwen*coder*`, `deepseek-coder*`, `codestral*`,
`starcoder*`). Copy the tokens from the model's own documentation —
a wrong template silently produces garbage completions.

FIM is **gated by a separate setting**: `ai.alias.default.fim` set to
the model spec (e.g. `lmstudio:qwen3-coder-30b`) switches the
follow-up endpoint's edit mode onto the completion path; unset = chat
path (default). Never point it at a chat model — missing
`fimTemplate` is a hard error by design.

### Image-only fields (when `kind: image`)

```yaml
kind: image
supportedAspectRatios: ["1:1", "16:9", "9:16", "4:3", "3:4"]
maxPromptChars: 4000
costPerImage:
  standard: 0.04
  hd: 0.08                              # only if the model has tiers
timeoutSeconds: 360
```

## Per-field merge

Each layer in the cascade only overrides the fields it explicitly sets;
omitted fields inherit. **Only carry the fields the user actually wants to
change.** Concrete examples:

```yaml
# Override only pricing — keep bundled context window, size, capabilities.
pricing:
  currency: USD
  inputPerMTok: 2.50
  outputPerMTok: 10.00
```

### Machine-owned pricing files (`auto: true`)

When a gateway's listing endpoint reports prices (cortecs does, in EUR
per MTok), discovery writes them into the manual layer itself:
`_vance/model/<provider>/<slug>.yaml` with an `auto: true` marker at
the top. The contract:

- No file yet → discovery creates one with the marker and the
  `pricing:` block. No hand work needed.
- File with the marker → machine-owned, the prices are refreshed on
  every discovery run (prices change — nobody wants to re-type them).
- File without the marker → operator-owned, discovery never touches
  it again.

To correct a machine-written price, edit the file AND remove the
`auto: true` line — otherwise the next run overwrites the correction.
To hand a model back to automation, re-add the marker.
```yaml
# Bump a known model's context window because the operator's plan
# unlocked a longer window than the bundled value.
contextWindowTokens: 1000000
```

Lists (`capabilities`, `supportedAspectRatios`) are replaced as a whole.
To remove a capability, list everything the model still supports and
omit the dropped one.

## Workflow

1. **Pick the scope.** Ask the user (or infer) whether the override
   should land **tenant-wide** (project `_tenant` — default for pricing
   edits, applies to every project under this tenant) or **only in one
   specific project** (the project name as the user knows it). The
   active project is **not** the right default — most operator edits
   should be tenant-wide.
2. **Pick the path.** `_vance/model/<provider>/<slug>.yaml`. Provider
   directory matches the wire-protocol name; slug is the wire-model
   name (with the `wireName:` field workaround for `:`-style tags).
3. **Read before write.** Call `doc_read` with the chosen
   `projectId` + `path` first. The operator may already have a
   partial override; merge with it rather than overwrite.
4. **Use the project-aware document tools.** Always pass an explicit
   `projectId` parameter — **do not** rely on the active-project
   default. Operator edits to the model catalog typically target a
   *different* project than the chat session is currently in.
   - **`doc_write`** to create the file or replace its full content
     (it upserts by path — creates when missing, overwrites when it
     already exists). Set `projectId: <_tenant or chosen project
     name>`, `path: "_vance/model/<provider>/<slug>.yaml"`, `kind:
     "yaml"`, `content: <yaml>`.
   - **`doc_edit`** for surgical changes (e.g. flip one pricing value)
     when the rest of the file should stay verbatim.
5. **Write minimal YAML.** Only the fields that actually change;
   everything else inherits from the next outer cascade layer. Always
   set `discoveredBy: manual` so the file is unambiguously
   distinguishable from auto-discovery output.
6. **Tell the user how to make it visible.** The catalog auto-refreshes
   every 30 minutes; for immediate effect the user clicks **Profile →
   Actions → Refresh AI Model Catalog**. Your own manifest has no
   refresh tool — the creator's `ai_models_discover` refreshes as a
   side effect of a discovery run, so delegate there or never claim
   to have refreshed anything from your side.
7. **Confirm in chat.** Quote the new path + the fields you set so the
   operator can sanity-check.

## Anti-patterns

- **Writing to `_vance/model-auto/**`.** That path is automation-owned;
  the next discovery run will overwrite your edits.
- **Copy-pasting the full bundled YAML.** Merge is per-field — drop the
  fields you don't change. A 200-line override is a smell.
- **Inventing capabilities the model doesn't support.** Adding
  `thinking` to a non-reasoning model causes provider-side 400s. Only
  list what the user has verified.
- **Inventing a FIM template.** Only copy a template from the model's
  own documentation — the control tokens are part of its training.
- **Pricing hallucinations.** If unsure, ask the user for the vendor's
  pricing page URL and confirm before writing — never guess prices.
  When the endpoint reports prices itself, discovery already wrote an
  `auto: true` file with them (see above) — only write a manual price
  when correcting one of those, and then REMOVE the `auto: true` marker
  so the next run does not overwrite your correction.
- **`discoveredBy: discovery-job` in a manual file.** That marker is
  reserved for the auto layer. Manual = manual.

## Related

- `manual_read('storage-surfaces')` — Document vs. Scratch vs. Client-File
- Run discovery: `POST /brain/{tenant}/admin/ai-models/discover` (the
  UI button, or the creator's `ai_models_discover` tool)
- Refresh in-memory catalog: `POST /brain/{tenant}/admin/ai-models/refresh`
  (operator-triggered via the UI button)
