# Area: Research

The Zarniwoop sources under `_vance/config/research/` — **one file per
search source**. The research dispatcher uses these documents to decide
which external services (search engines, APIs) get queried and with
which credentials.

## What you configure here

- **Protocol** — the kind of source (e.g. `serper`, `tavily`). A
  protocol this build does not know passes through as a raw value
  instead of being snapped to a known one.
- **Endpoint and credential** — base URL and API key per source. The
  key belongs in here as a vault reference (`{{secret:vault:…}}`),
  not in plaintext.
- **Enabled** — a source can be paused without deleting it.

## Scopes

Sources apply per project; the tenant (`⌂`) provides defaults that a
project overrides by name. New names add to the list.

The listing here filters by the source kind — research sources only,
nothing else from the folder.
