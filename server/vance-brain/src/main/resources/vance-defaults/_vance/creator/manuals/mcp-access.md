---
audience: creator
triggers: mcp, mcp access, model context protocol, external agent, coding agent, claude code, cursor, mcp server, mcp zugriff, externen agenten anbinden, agenten zugriff auf das projekt, projekt mcp, mcp einrichten, mcp-access.yaml, give an agent access to the project, dokumente fuer einen agenten freigeben, reglementierter zugang
summary: How I open project document access for an external agent (Claude Code, Cursor, any MCP host) — the project route /brain/{tenant}/mcp/{project} with a fixed doc_* tool set, regulated by _vance/config/mcp-access.yaml (ro/rw, path prefixes, per-token entries, first-match-wins, fail-closed deny). The agent's credential is an integration token with the mcp-project profile, minted on the user's human surface. Writing the config needs ADMIN.
requires-tools: doc_write, doc_edit, doc_list_in_folder
---
# How I open project access over MCP

An **MCP host** is an external agent tool that speaks the Model Context
Protocol — Claude Code, Cursor, any MCP client. Vance has two doors:

| Door | Route | Surface | Regulated by |
|---|---|---|---|
| **Project** | `/brain/{tenant}/mcp/{project}` | fixed `doc_*` set only | `_vance/config/mcp-access.yaml` per project |
| Global | `/brain/{tenant}/mcp` | the full tool catalogue | the service account's permission grants |

The **project door** is the one to set up for a user who wants "give my
coding agent access to the spec documents": narrow, document-shaped,
fail-closed. The global door is for closed test systems with a
deliberately broad service account — not something I set up on request
without saying what it opens.

## Before the first write

- **Rights**: writing `_vance/…` needs ADMIN. A denied write means the
  delegating user is not an operator for this project — say so plainly,
  do not retry.
- **Check what exists**: `doc_list_in_folder` with `pathPrefix:
  _vance/config` — an `mcp-access.yaml` may already be there (mine or
  the `_tenant` fallback's).

## The config document

`_vance/config/mcp-access.yaml` — plain YAML, no kind marker, no form:

```yaml
default: deny
access:
  - name: claude-code
    token: "<integration-token-id>"   # optional; absent = any caller
    mode: rw                          # ro | rw
    paths: ["specs/", "readme/"]     # prefixes; absent = whole project
  - name: humans
    mode: ro
```

Semantics I promise the user:

- **First match wins** in document order; no matching entry = deny. Put
  token-pinned entries **above** catch-all ones.
- **`ro`** exposes `doc_read`, `doc_read_lines`, `doc_list_in_folder`,
  `doc_list_folders`, `doc_grep_path`; **`rw`** adds `doc_write`,
  `doc_edit`, `doc_replace_lines`. Nothing else — no delete, no move,
  no other tool families, and the list is fixed, not configurable.
- **`paths`** are folder prefixes (no leading slash). With prefixes set,
  every call must name its path explicitly — no default scope, no `*`.
- A missing or malformed file means **deny** (plus a server warn log),
  never an open surface. `default:` exists only as `deny`.
- **Placement**: the project tier wins, `_tenant` is the fallback — an
  operator can pre-regulate every project of a tenant from the system
  project.

## What the surface enforces regardless of the config

The agent addresses documents **by `path` only** (`id` arguments are
rejected), cannot leave the route's project (`projectId` must match or
be omitted), and can **never write `_vance/`** — hard, not configurable,
so the agent cannot rewrite the config that regulates it. The config is
a ceiling on a smaller surface, never a grant: writes still need the
caller's real WRITE rights, so `rw` + a read-only account fails at the
document.

## The agent's credential

A long-lived **integration token with the `mcp-project` profile**,
pinned to this one project. Such a token reaches only the project route
— it cannot call the global MCP door. I cannot mint it: minting is a
credential act on the user's own human surface (their access token,
`POST /brain/{tenant}/integration-tokens` with `scopeProfiles:
["mcp-project"]` and `projectId`). Tell the user to mint it and give
them this client config:

```json
{
  "mcpServers": {
    "vance-<project>": {
      "type": "http",
      "url": "https://<brain>/brain/<tenant>/mcp/<project>",
      "headers": { "Authorization": "Bearer <integration-token>" }
    }
  }
}
```

If they prefer their own login: a normal access token works on the same
route and its grants apply — but an access token also reaches the global
door; only the integration token is route-narrowed.

## Verifying

Changes take effect on the next call (invalidation is event-driven).
Ask the user to run `tools/list` after connecting — the tool list must
match the entry's mode (5 tools `ro`, 8 tools `rw`) and a path outside
the prefixes must come back as an error text, not a crash.
