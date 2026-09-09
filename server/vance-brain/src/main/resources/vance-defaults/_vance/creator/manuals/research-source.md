---
audience: creator
triggers: search source, research source, search provider, research provider, add a search backend, add a research provider, suchquelle, suchquelle anlegen, such-anbieter hinzufügen, provider hinzufügen, provider einrichten, serper, serper key, api key fuer die suche, search key, search config, research endpoint, research.endpoint, web search einrichten, eigene suche, _vance/config/research, no provider for modality, kein provider
summary: How I add or change a research source — one YAML document per provider instance under _vance/config/research/, filename = instance id, protocol-picked fields, apiKey as vault reference or {noop} literal, tenant-wide (_tenant) vs project placement, and verification with research_providers. Writing there needs ADMIN; routing defaults are settings I cannot write.
requires-tools: doc_write, doc_edit
---
# How I set up research sources

A **research source** is one configured search backend: Serper.dev, a
Wikipedia language edition, arXiv — or a foreign application speaking
the `ode` contract. The `research_search` family dispatches across
whatever is configured; `research_providers` lists the live inventory.

Each source is **one YAML document**. There is no settings form for this
anymore — the old `research.endpoint.*` settings are gone, and the
document is the configuration.

## Before the first write

- **Rights**: writing `_vance/config/research/` needs ADMIN. When the
  write comes back denied, the delegating user is not an operator —
  say so plainly, do not retry and do not offer workarounds.
- **Check what exists**: `research_providers` first. The source the
  user wants may already be there under a different id.

## The document

`_vance/config/research/<id>.yaml` — **the filename is the instance id**
(`serper-main.yaml` → `serper-main`). It shows up in `research_providers`
output, in cooldown subjects and in the logs.

Serper example:

```yaml
protocol: serper
baseUrl: https://google.serper.dev
apiKey: "{{secret:vault:research.serper-main}}"
enabled: true
```

| Field | Required | Meaning |
|---|---|---|
| `protocol` | yes | the wire adapter: `serper`, `wikipedia`, `hackernews`, `openalex`, `arxiv`, `pubmed`, `openlibrary`, `ode` |
| `baseUrl` | protocol-dependent | Serper, Wikipedia and `ode` need it; the keyless academic/news protocols have built-in defaults |
| `apiKey` | protocol-dependent | credential **as written**: a `{{secret:vault:…}}` reference or a `{noop}…` literal. Never write a bare secret |
| `enabled` | no | absent counts as `true`; `false` keeps the instance configured but out of rotation |

Everything else the document declares travels to the protocol as-is —
`contactEmail` for OpenAlex, `capsTtlSeconds` for `ode`, and so on.

## Where the document goes

- **`_tenant` project** → tenant-wide default; every project of the
  tenant sees the source. A bought API key belongs here.
- **a user project** → project-scoped. Same filename as a `_tenant`
  entry means whole-document replacement in that project — there is no
  per-field merge.

## Built-in protocols

| protocol | Credential | Serves |
|---|---|---|
| `serper` | API key (free tier ~2 500/month) | web, image, video, pdf, news — one key for all |
| `wikipedia` | — | encyclopedia; `baseUrl` picks the language edition |
| `hackernews` | — | news, tech focus |
| `openalex` | — | academic; `contactEmail` gets the polite pool |
| `arxiv` / `pubmed` | — | academic, STEM / medicine |
| `openlibrary` | — | book |
| `ode` | optional bearer token | whatever the far end declares — read `manual_read('ode-search-source')` before promising anything about it, and remember its far end needs code nobody here controls |

## Routing is a setting — not mine

Which instance serves a modality by default lives in the settings
`research.default.<modality>` and `research.fallback.<modality>` (e.g.
`research.default.web = serper-main`). Settings are operator territory
and I have no tool for them — name the keys to the operator. The
keyless sources are routed out of the box, so a new keyless source is
found by `research_search` without any routing change; a new keyed
source usually wants `research.default.web` pointed at it.

## Verify

Writing a source-config document evicts the provider cache, so the
inventory is fresh immediately: `research_providers` right after the
write must list the new id. Availability `NO_CREDENTIALS` means the key
reference does not resolve — fix the document (or the vault entry),
then check again. When done, state plainly what was created (id,
protocol, placement) and which modality it serves.
