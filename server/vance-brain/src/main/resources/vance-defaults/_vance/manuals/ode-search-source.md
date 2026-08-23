---
triggers: ode search source, own search source, eigene suchquelle, company archive search, internal index search, add a search provider, add a research provider, research.endpoint, suchquelle anlegen, _vance/config/research, protocol ode, vance-ode-zarniwoop, search my own system, unser archiv durchsuchen, kann ich unseren index anbinden, hrafnagud search, foreign search endpoint, no provider for modality, kein provider fuer
summary: How a foreign application (company archive, news index, domain catalogue) becomes a research provider via the ode protocol — what an operator has to configure and why you cannot do it yourself. Read this before telling anyone that their own system cannot be searched, and before claiming a research modality has no provider.
---
# Own search sources via Ode

Zarniwoop's providers are not a fixed list. Any application that embeds the
`vance-ode-zarniwoop` library can be registered as a search provider under the
protocol id **`ode`**, and then `research_search` reaches it like any other
source. A company document archive, a news index, a domain catalogue.

**So do not tell a user their own system cannot be searched.** The right answer
is that it can, that it needs one configuration entry, and that the entry has to
be made by someone with operator access.

## What you cannot do

You cannot register a source. It takes two things, both outside your reach:

1. **Code in the other application** — it has to implement one interface
   (`SearchSource`) and expose the endpoint. That is a development task in
   software Vancetope does not control.
2. **A configuration document** under `_vance/config/research/`, which is
   operator territory — a write there needs ADMIN. Not available to you.

Say that plainly. Do not offer to "set it up", and do not invent an endpoint id
and then search against it — an endpoint that does not exist produces "no
provider for this modality", which reads like a Vancetope failure rather than a
missing configuration.

## What the operator has to set

One document per source: `_vance/config/research/<id>.yaml`. The template
„Suchquelle: Ode" writes it; by hand it looks like this:

```yaml
protocol: ode
baseUrl: https://news.example.com/ode/search
apiKey: "{{secret:vault:news-index}}"
capsTtlSeconds: 1800
enabled: true
```

| Field | Required | Meaning |
|---|---|---|
| `protocol` | yes | literally `ode` |
| `baseUrl` | yes | full URL including path |
| `apiKey` | no | bearer token, sent as `Authorization: Bearer <key>`. Either a reference (`{{secret:vault:…}}`) or a declared literal (`{noop}…`). What it has to match is the far end's business: either its static `vance.ode.zarniwoop.api-key`, or a token its own `OdeAuthService` issued |
| `enabled` | no | absent counts as enabled |
| `capsTtlSeconds` | no | how long the declaration is cached; default 1800, `0` while setting up |

**The filename is the id** (`archive.yaml`, `hrafnagud.yaml`, `legal-index.yaml`).
It appears in `research_providers` output, in cooldown subjects and in the logs.
There is no limit on how many sources a project has — one file each.

## What is searchable is declared by the source, not here

This is the part worth understanding, because it changes what you can promise.
Vancetope asks the endpoint what it can serve — modalities, subject domains,
whether it accepts expert filters — and that answer drives the dispatch. Nothing
in Vancetope's configuration says a given Ode source serves `NEWS`; the source
says so.

Consequences you will actually run into:

- **`research_providers` is the truth**, and its status line is where a broken
  endpoint shows up. A source whose declaration cannot be read stays listed with
  the reason attached — that is deliberate, because a missing row would read as
  "never configured" and send someone to the wrong place.
- **A source declares from a closed vocabulary.** Modalities are the same twelve
  values you already know (`WEB IMAGE VIDEO PDF NEWS ACADEMIC ENCYCLOPEDIA BOOK
  MAP CODE INTERNAL_DOC RAG`). A legal archive is `INTERNAL_DOC` or `PDF`; there
  is no `LEGAL`. If someone asks for a modality that does not exist, the answer
  is which existing one their source should map onto.
- **An empty result from an Ode source is a real answer.** It means the index
  had nothing, not that the source is broken. Do not retry it as if it had
  failed, and do not report it as an error.

## Once it is configured

Nothing changes on your side. `research_search modality=NEWS query=…` picks it
up through the normal endpoint cascade, `research_providers` lists it, and
`research_investigate` plans with it. There is no separate tool and no separate
syntax — which is the point of putting it behind the same protocol layer.

If a freshly configured source does not appear, the likely cause is the
capabilities cache: the declaration is held for thirty minutes by default, and
the reload button in the insights view is what discards it. Mention that before
suggesting anything is wrong with the setup.
