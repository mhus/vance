---
audience: creator
triggers: mcp json, mcp.json, client tool pack, tool pack, tool packs, mcp pack, mcp server einrichten, chrome devtools mcp, chrome mcp, browser tools, mcp tools fehlen, pack failed to start, npx enoent, lokale tools einbinden, mcp durchreichen, client tools, mcp.json anlegen, tool pack einrichten
summary: How a user connects MCP servers to their Vance CLIENT (foot or the desktop app) — the tool packs: one shared ~/.vancetope/mcp.json (mcpServers map) plus an optional project-level file at the working directory, selection via config.yaml, a trust dialog for project packs. The pack's tools surface as <pack>__<tool> client tools with labels. Creating the file needs a desktop session (client_file_write) — in a web session I hand the JSON to the user instead.
requires-tools: client_file_write, client_file_read
---
# How I connect MCP servers to a user's client (tool packs)

Vance runs MCP **server-side doors** for external agents (see the
`mcp-access` manual). Tool packs are the opposite direction: the USER's
client — the desktop app (Vancetope), and the foot in the shape it
supports — **starts MCP servers locally** and passes their tools through
to me as client tools. The user asks for "browser tools" or "connect
chrome" — this is how.

## The one file that matters: `~/.vancetope/mcp.json`

A single `mcpServers` map — the format the user may already know from
other tools. The key is the pack name; the fields are ours:

```json
{
  "mcpServers": {
    "chrome": {
      "command": ["npx", "-y", "chrome-devtools-mcp@latest"],
      "labels": ["browser"]
    },
    "jira": {
      "command": ["jira-mcp", "--cloud"],
      "labels": ["jira"],
      "enabled": false,
      "disabledSubTools": ["dangerous_reset"]
    }
  }
}
```

| Field | Meaning |
|---|---|
| `command` | argv of the server process (stdio transport). `npx -y …` is common; the binary must exist on the user's machine |
| `labels` | how recipes address the pack as a capability (`@browser`) — the client adds `mcp`, `mcp:<pack>`, `side-effect` automatically |
| `enabled` | `false` silences the pack without deleting it |
| `disabledSubTools` | per-tool off-switch inside the pack |

**Tools surface as `<pack>__<subtool>`** (`chrome__take_snapshot`) — one
budget family per pack, deferred by default (reachable via `tool_list`
without flooding the manifest). Pack calls are ungated in v1: loading
the pack IS the consent; only the **project-level** variant asks first.

## The two levels

| Level | File | Who trusts it |
|---|---|---|
| global | `~/.vancetope/mcp.json` | the user wrote it — no prompt |
| project | `<workdir>/.vancetope/mcp.json` | comes with the working directory (a cloned repo!) — a **trust dialog** asks once: Load once / Always for this workdir / Don't load. "Always" matches name + command; a swapped command re-asks. Never auto-load a project pack silently |

Merge: union over the pack name, the project level wins (contribute,
redirect, silence with `enabled: false`).

Selection lives separately in `<workdir>/.vancetope/config.yaml`:

```yaml
toolPacks:
  enabled: true          # false = no packs in this project
  packs: [chrome]        # allow-list; absent = all
  disabledPacks: [jira]  # deny-list, applied after packs
```

## What I can do — and what the user must do

- **Desktop session:** I create/extend `~/.vancetope/mcp.json` directly
  with `client_file_write` (it is on the user's machine — outside the
  workspace). Then tell the user to hit **"Reload tool packs"** in the
  app's account management (Manage accounts → Edit account) and to
  re-bind the session — the tools register on the next bind.
- **Web session:** I have no local filesystem. I hand the JSON block to
  the user to save as `~/.vancetope/mcp.json` themselves.
- **Client support:** the desktop app reads the mcp.json today; the
  foot still uses its own legacy pack directories (`~/.vancetope/
  foot-tools/`) — the shared file for foot is on the roadmap. When in
  doubt, ask which client the user works with.

## When a pack does not show up

The desktop app **shows failed packs with their error** (insights →
Tools → "this app's tool inventory", and the reload status). Common
causes:

- `spawn npx ENOENT` → no Node/npx on the user's PATH for app-launched
  processes. The app resolves the login-shell PATH, but a headless Node
  install can still be missing — the fix is on the user's machine, not
  in Vance.
- A first `npx …` run downloads the server — the first materialization
  can take seconds; a rebind after that shows the tools.
- `enabled: false` or the `toolPacks` selection filtered the pack out —
  check the config before blaming the server.

I never invent MCP server commands: the user names the server they want
(or I name a well-known one, e.g. chrome-devtools-mcp for browser
control) and the pack definition states exactly what will run.
