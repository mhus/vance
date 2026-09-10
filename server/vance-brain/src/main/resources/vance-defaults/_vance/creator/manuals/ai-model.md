---
audience: creator
triggers: model definitions, modell definitions, modell definitionen, modell definition anlegen, neuer llm provider, llm provider anlegen, llm provider einrichten, provider instance, provider instanz, ai-model, ai-models, modell hinzufügen, modell-doc schreiben, model catalog, modell katalog, openai compatible, openai kompatibel, openai-wire gateway, endpoint registrieren, _provider.yaml, _vance/model, model-auto, discover ai models, api key für llm provider
summary: How I register an LLM endpoint as a provider instance and lay down model definitions — `_provider.yaml` sidecar (wireType, never credentials), baseUrl/type settings I write myself via setting_set, the apiKey that stays with the operator, per-model docs under `_vance/model/` vs auto-discovery under `_vance/model-auto/`, plus an optional settings form so the endpoint is editable in the UI.
requires-tools: doc_write, doc_edit, setting_set, setting_get, ai_models_discover
---
# How I set up model definitions

A **model definition** registers an LLM endpoint with its models for
this project (or tenant-wide). Typical request: "there is a new
OpenAI-compatible provider at `<url>`, lay down the model definitions
here" — the user adds the API key themselves afterwards.

Three pieces, each with a fixed owner:

| Piece | Where | Owner |
|---|---|---|
| Endpoint facts (protocol) | `_vance/model/<instance>/_provider.yaml` | me |
| Credentials & base URL | settings `ai.provider.<instance>.*` | split — I write baseUrl/type via `setting_set` (ADMIN user); the apiKey is operator-only |
| Per-model metadata | `_vance/model/<instance>/<slug>.yaml` (manual) or `_vance/model-auto/…` (discovery) | me / automation |

## The sidecar — endpoint facts only

```yaml
# _vance/model/<instance>/_provider.yaml
displayName: Coding Proxy (OpenAI-wire gateway)
wireType: openai
authType: api-key
maxTools: 128            # optional: the endpoint's tool-array limit
tlsInsecure: true         # optional: private-CA gateway — trust-all TLS for chat + listing
```

- `wireType` binds the instance label to a wire protocol: `anthropic`,
  `openai`, `openai-experimental`, `gemini`, `ollama`, `ollama-cloud`,
  `lmstudio`, `azure-openai`. A setting `ai.provider.<instance>.type`
  would override it — it rarely needs to exist.
- **Never put `apiKey` or `baseUrl` in the sidecar.** The base URL is
  only ever read from settings — a sidecar value is silently ignored.
  A secret in a catalog document would ship with every kit export and
  every `doc_read`.
- `tlsInsecure: true` — for gateways on a private CA
  (`PKIX path building failed` on every call). Routes this instance's
  chat and model-listing traffic through a trust-all TLS context.
  Per-instance opt-in, ADMIN-written document, validation stays on for
  everything else. The cleaner alternative is importing the CA into the
  JVM truststore (global); this flag is the scoped fix. Embedding
  endpoints are not covered.

## The settings — plain ones I write, credentials stay with the user

With `setting_set` I write plain settings when the invoking user is
ADMIN on the target scope (this recipe is granted `ai.provider.*`
writes for `type` and `baseUrl`); `setting_get` reads a key back for
verification — the read/write rules of the settings tools live in
`manual_read('settings-tools')`. The keys:

- `ai.provider.<instance>.apiKey` — **operator territory**: I cannot
  write it (password settings are agent-proof). Name the key, point the
  user at the settings form or editor, and continue once it is set.
- `ai.provider.<instance>.baseUrl` — **required for every custom
  gateway**; I write it myself via `setting_set`. Left unset, the
  instance talks to the protocol's home endpoint: an OpenAI-wire key
  would be sent to api.openai.com. Anthropic and Gemini adapters have
  no chat `baseUrl` at all.
- `ai.provider.<instance>.type` — optional; only when a per-tenant
  override of the sidecar's `wireType` is wanted (the sidecar is the
  normal place for it).

Placement of the settings decides the reach: settings in a user
project scope the instance (and its auto-discovered model docs — same
project, by design) to that project; settings in `_tenant` make it
the tenant-wide default.

## Per-model documents

`_vance/model/<instance>/<slug>.yaml` — the manual layer, survives every
discovery run. The slug is the wire model name; `:` becomes `-` plus an
explicit `wireName:` field, `/` becomes a subdirectory.

```yaml
contextWindowTokens: 131072    # mandatory; everything below optional
size: LARGE                    # SMALL | LARGE — drives recipe-tier choice
kind: chat                     # chat | image
capabilities: [vision, pdf, thinking]   # only what the user verified
pricing:                       # never guess — ask for the vendor's page
  currency: USD
  inputPerMTok: 3.00
  outputPerMTok: 15.00
discoveredBy: manual           # always this value on this path
```

Merge is **per-field** through the cascade (bundled → system tenant →
`_tenant` → project; manual beats auto within a scope): write only the
fields that change, the rest inherits. OpenAI-wire quirks that hard-fail
as HTTP 400: `outputTokenParam: max_completion_tokens` for reasoning
models (bundled patterns already cover `gpt-5*` / `o1*` / `o3*` /
`o4-mini*`), `unsupportedParams` for models that reject `temperature`
and friends. When unsure how a field behaves in the running version,
verify against the sources — `manual_read('vance-sources')`.

## Or let discovery do the listing

When the endpoint's model list should not be curated by hand, run
`ai_models_discover` — the tool walks every project's
`ai.provider.<instance>.*` settings, calls each provider's listing
endpoint and writes `_vance/model-auto/<instance>/<slug>.yaml`
(overwritten on every run — never write or "fix" anything there).
It needs tenant ADMIN; when the call comes back denied, say so
plainly and tell the user the button does the same: Profile →
Actions → Discover AI Models. Auto-docs carry the observations the
listing endpoint reports — `contextWindowTokens`, `maxOutputTokens`,
`ownedBy` (OpenAI proper gives only `id`/`owned_by`; cortecs adds
context size and output limit). Prices the endpoint reports land in
the MANUAL layer — `_vance/model/<instance>/<slug>.yaml` with
`auto: true` — created when no file exists, refreshed on every run
while the marker stays, never touched once an operator removes it.
`kind` and capabilities still never come from discovery — those need a
manual doc on top.
catalog, so manual docs written before the call become visible in
the same breath — without it the refresh runs every 30 minutes
(or Profile → Actions → Refresh AI Model Catalog, operator-only).

## Optional: a settings form for the UI

So the user can maintain key and endpoint from the UI, I can write
`_vance/setting_forms/llm-provider-<instance>.yaml` — same shape as the
bundled ones (localized `title`/`description`, `category: llm`, one
`password` field binding `…apiKey`, one `string` field binding
`…baseUrl`):

```yaml
title: { de: "LLM-Provider: Coding Proxy", en: "LLM provider: Coding Proxy" }
description: { de: "Zugangsdaten der Instanz coding-proxy.", en: "Credentials for the coding-proxy instance." }
category: llm
fields:
  - name: apiKey
    type: password
    label: { de: "API Key", en: "API Key" }
    bindsTo: { key: "ai.provider.coding-proxy.apiKey" }
  - name: baseUrl
    type: string
    required: true
    label: { de: "Base-URL", en: "Base URL" }
    bindsTo: { key: "ai.provider.coding-proxy.baseUrl" }
```

## Workflow

1. `doc_list` on `_vance/model/` — the instance may already exist; then
   update instead of creating a duplicate.
2. Pick the instance name: `[a-z0-9._-]+`. It becomes the prefix of
   every `<instance>:<model>` spec the user can then reference.
3. `doc_write` the sidecar, then the model docs (omit `kind` — the YAML
   body lives in a plain text doc, like the discovery job writes them).
4. Set the baseUrl via `setting_set` (needs an ADMIN user; projectId
   `_tenant` for tenant-wide) and confirm it with `setting_get`. Name the
   apiKey key for the operator — passwords stay on the human surfaces.
5. Run `ai_models_discover` once the operator has set the apiKey —
   the call also makes the sidecar and manual docs visible via the
   catalog refresh.
6. Confirm in chat: paths written, fields set, settings keys named.

## Anti-patterns

- `baseUrl` or `apiKey` in the sidecar — ignored / leaks via kit export.
- Writing under `_vance/model-auto/**` — automation-owned.
- Copy-pasting a full bundled model YAML — per-field merge wants
  minimal overrides.
- Inventing capabilities or prices — ask for the vendor's page.
- Narrating discovery or refresh success without a tool result —
  `ai_models_discover` reports what it wrote; when it comes back
  denied, the user clicks the button themselves.
- Writing or asking for the apiKey — password settings are refused by
  `setting_set` by design; hand the key entry to the operator.

## Related

- `manual_read('vance-sources')` — verify the schema against the
  running version's sources.
- `manual_read('storage-surfaces')` — where documents live at all.
- `manual_read('ai-alias')` — pointing `ai.alias.default.*` at the new
  instance so recipes actually use it (the natural finishing step
  after discovery).
