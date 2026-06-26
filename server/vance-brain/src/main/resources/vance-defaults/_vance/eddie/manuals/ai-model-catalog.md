---
triggers: ai-model pricing eintragen, neues llm modell katalog, modell hinzufügen vance, ai model catalog override, pricing für modell, kontextfenster modell setzen, modell-doc schreiben, ai-models yaml, model catalog manual, modell konfigurieren brain
summary: How to author a MANUAL-layer AI-model metadata doc (pricing, context window, capabilities) at `_vance/model/<provider>/<slug>.yaml`. Use when the user wants to register, override, or price a model that auto-discovery cannot fill in. Do NOT write to `_vance/model-auto/**` — that is automation-owned.
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

- "Trag bitte Pricing für claude-sonnet-4-7 ein"
- "Vance kennt das neue model X nicht — füg es hinzu"
- "Override context window für gpt-5 auf 1M tokens"
- "Kennzeichne deepseek-v4 als Reasoning-Modell"
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

pricing:                                # USD/EUR/… per 1M tokens
  currency: USD
  inputPerMTok: 3.00
  outputPerMTok: 15.00
  cacheReadPerMTok: 0.30                # only when the provider supports prompt cache
  cacheWritePerMTok: 3.75

discoveredBy: manual                    # ALWAYS this — marker that an operator wrote it
discoveredAt: "2026-06-27T10:00:00Z"    # optional, informational
```

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
   - **`doc_create`** when the file doesn't exist yet. Set
     `projectId: <_tenant or chosen project name>`, `path:
     "_vance/model/<provider>/<slug>.yaml"`, `kind: "yaml"`,
     `content: <yaml>`.
   - **`doc_write`** when the file already exists and the user wants
     to replace its full content. Same `projectId` + `path`.
   - **`doc_edit`** for surgical changes (e.g. flip one pricing value)
     when the rest of the file should stay verbatim.
5. **Write minimal YAML.** Only the fields that actually change;
   everything else inherits from the next outer cascade layer. Always
   set `discoveredBy: manual` so the file is unambiguously
   distinguishable from auto-discovery output.
6. **Tell the user how to make it visible.** The catalog auto-refreshes
   every 30 minutes; for immediate effect the user clicks **Profile →
   Actions → Refresh AI Model Catalog**. There is **no LLM-callable
   refresh tool** — never claim to refresh from your side.
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
- **Pricing hallucinations.** If unsure, ask the user for the vendor's
  pricing page URL and confirm before writing — never guess prices.
- **`discoveredBy: discovery-job` in a manual file.** That marker is
  reserved for the auto layer. Manual = manual.

## Related

- `manual_read('storage-surfaces')` — Document vs. Scratch vs. Client-File
- Run discovery: `POST /brain/{tenant}/admin/ai-models/discover` (operator
  triggers this via the UI button — you do not have a tool for it)
- Refresh in-memory catalog: `POST /brain/{tenant}/admin/ai-models/refresh`
  (same; operator-triggered)
