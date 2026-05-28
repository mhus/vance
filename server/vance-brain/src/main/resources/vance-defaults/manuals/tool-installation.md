---
triggers: install tool, set up service, "richte mir X ein", "verbinde Vance mit", "ich will X nutzen", tool_template_list, tool_template_install, integration setup, configure Jira, configure Slack, configure GitHub
summary: How to install a pre-configured tool template (Jira, IMAP, SMTP, Slack, GitHub, …) for the user via the tool-template catalog.
---
# Installing tools from templates

How to set up Jira, IMAP, SMTP, Slack, etc. via the tool-template
catalog — without the user editing YAML or hitting curl. The tenant
admin curates the catalog; you (the agent) drive the install.

## When to use this

The user says any of:
- "richte mir <service> ein" / "set up <service>"
- "verbinde Vance mit <service>"
- "ich will <service> nutzen" / "I want <service>"

…and `<service>` is a known integration (Jira, IMAP, SMTP, Slack,
GitHub, …). Don't guess — check the catalog first.

## The three tools

```
tool_template_list                     → list everything available
tool_template_describe(name)           → fetch the input schema for one
tool_template_apply(name, projectId, inputs, token?)
                                       → install it with the user's inputs
```

All three are deferred-by-default (`@tool-template` label). Find them
via `find_tools(query="tool_template")`. Promote with `invoke_tool` once
the user has confirmed a direction.

## Canonical flow

```
1. user: "set up Jira"  /  "set up Atlassian"

2. agent:
   find_tools(query="tool_template")
   → confirms the three tools exist

3. agent:
   invoke_tool(tool_template_list, {})
   → templates: [{name: "atlassian", title: "Atlassian (Jira + Confluence)", ...}, ...]

4. agent:
   invoke_tool(tool_template_describe, {name: "atlassian"})
   → inputs:
       - {name: "features", type: "multi_select", required: true,
          choices: [{value: "jira", label: "Jira", defaultSelected: true},
                    {value: "confluence", label: "Confluence", defaultSelected: false}]}
       - {name: "clientId", type: "string", required: true, ...}
       - {name: "clientSecret", type: "password", target: "setting", ...}
   → postInstall: {kind: "oauth-connect", provider: "atlassian", ...}

5. agent → user (ASK_USER):
   "Brauche drei Werte:
   - Welche Atlassian-Produkte? (Jira / Confluence / beides)
   - OAuth Client ID (developer.atlassian.com/console → deine App → Settings)
   - OAuth Client Secret (wird verschlüsselt in den Tenant-Settings abgelegt)"

6. user provides values

7. agent:
   invoke_tool(tool_template_apply, {
     name: "atlassian",
     projectId: "_tenant",
     inputs: {
       features: ["jira"],          // or ["jira", "confluence"]
       clientId: "...",
       clientSecret: "..."
     }
   })
   → applied: true, postInstall: {kind: "oauth-connect", provider: "atlassian", ...}

8. agent → user (ANSWER):
   "Atlassian-Provider ist konfiguriert (Jira-Pack installiert). Letzter Schritt:
   öffne Connected Accounts in der Web-UI (http://localhost:9900/connected-accounts.html)
   und klick bei 'atlassian' auf Connect. Danach stehen die Jira-Tools zur Verfügung."
```

## Multi-select inputs (v2 templates)

Some templates (e.g. `atlassian`, future `google-workspace`) have an
input of type `multi_select` — a feature-picker that decides which
parts of the kit get installed and which OAuth scopes get requested.

The describe response looks like:

```
{
  name: "features",
  type: "multi_select",
  required: true,
  choices: [
    {value: "jira",       label: "Jira",       defaultSelected: true},
    {value: "confluence", label: "Confluence", defaultSelected: false}
  ]
}
```

When apply'ing, pass the selection as a JSON array of `value`s:

```
inputs: { features: ["jira", "confluence"], ... }
```

Both `["jira"]` and `["jira", "confluence"]` are valid; the empty
array is rejected when `required: true`. Comma-separated strings
(`"jira,confluence"`) are also accepted as a fallback for chat-style
inputs.

**Re-apply with a different selection is the supported upgrade path.**
"User has Jira, now wants Confluence too" → re-call `tool_template_apply`
on the same template with `features: ["jira", "confluence"]`. The OAuth
provider yaml is rewritten with the wider scopes, the second REST pack
is installed; tell the user to **re-Connect** in the Web-UI so the
provider's consent screen acknowledges the new scopes.

## Re-checking previous installs (applied state)

If the user says "what did I configure for Atlassian last time?", you
can call:

```
GET /admin/tool-templates/<name>/applied?projectId=<p>
```

via the REST surface (no tool wrapper yet — use the standard REST
helper). The response carries `template`, `appliedAt`, `appliedBy`,
`features`, and the non-PASSWORD `inputs` from the last apply. Password
fields are structurally absent — the user has to re-enter them only
when they want to change them.

## Choosing the target project

Most templates go into `_tenant` — that's the tenant-wide default
project and tools placed there cascade into every other project. Use
`_tenant` unless:

- The user explicitly wants the integration **only** in a specific
  project (e.g. "diese Jira-Anbindung nur für das Sales-Projekt").
- The template's secrets are user-specific (then `_user_<login>`).

The describe response doesn't tell you which target to use — that's a
deployment decision. When in doubt, `_tenant` and tell the user.

## Handling secrets

PASSWORD-typed inputs are **never** shown back to the user. After
apply, the value is in SettingService (encrypted at rest); the kit's
documents reference it via `{{secret:user:...}}` or `{{secret:tenant:...}}`.

When asking for a password:
- Make clear it'll be stored encrypted in tenant/project/user settings
- Mention where the user gets it (e.g. "App-Password from
  accounts.zoho.com/security/app-passwords")
- Don't echo it back in any subsequent message

## Post-install hooks

When `apply` returns a `postInstall` block:

| kind | Action |
|---|---|
| `oauth-connect` | Tell the user to open Connected Accounts and click Connect for the named provider. Provide the direct URL: `http://localhost:9900/connected-accounts.html`. |
| `notice` | Pure informational. Relay `postInstall.message` to the user verbatim — it explains an out-of-band step (e.g. "now start the daemon with the following command"). No UI action wired. |

Without a `postInstall`, the template is fully set up — confirm to
the user and move on.

## Foot-daemon proxies

The `foot-daemon` template registers a remote foot daemon's tools as
sub-tools of this project. After apply the user must start the actual
daemon process on the target machine:

```bash
vance-foot --profile=daemon --project=<this-project> --name=<daemonName>
```

The daemon needs a service-account user (username prefixed with `_`,
created by tenant admin). Once it connects:

- Sub-tools surface as `foot-daemon__<sub>` (e.g. `foot-daemon__client_exec_run`).
- Offline daemons surface no sub-tools at all — wait for reconnect.
- Stale daemons (just-disconnected, within the 60-second grace window)
  are still listed but invokes throw `ToolException` with a clear
  "offline / reconnect" message.

When the chat user says "set up access to my prod server" / "register
the build host" / "give me a remote shell tool":

1. `tool_template_describe(name="foot-daemon")` → input schema
2. ASK_USER for `daemonName`, short `description`, optional timeout
3. `tool_template_apply(name="foot-daemon", projectId=<project>, inputs={...})`
4. Relay the `postInstall.message` (kind=notice) — the user starts the
   daemon process, you confirm the tools are visible via `find_tools`.

## Catalog discovery for the user

If the user asks "what can I install?" or "welche Tools gibt's?":
1. `invoke_tool(tool_template_list, {})`
2. Group by `category` field in the answer
3. Format each as: `**<title>** (`<name>`) — <description>`

## Errors you'll see

| Error | What to do |
|---|---|
| `Template 'X' is not in the tenant catalog` | Wrong name. Re-list and offer the correct ones. |
| `apply failed: required input 'X' is missing` | Re-ask the user for that field. Don't guess. |
| `apply failed: input 'X': value 'Y' not in choices [...]` | User picked a multi-select / select value that isn't declared. Re-ask, listing the valid choices from `describe`. |
| `apply failed: input 'features': at least one choice must be selected` | Multi-select is `required` and was passed empty. Re-ask which products. |
| `apply failed: project 'P' does not exist` | Wrong `projectId`. Use `project_list` to find the right one or default to `_tenant`. |
| `kit at ... is not a tool-template — no template.yaml` | Catalog entry points at a non-template kit. Report to the user; this is a tenant-admin config issue. |

## What this is NOT for

- **Whole-project setup** (`kit_install` / `project_create`) — those
  initialise a project from a kit. Tool-templates are additive into
  *existing* projects.
- **One-off documents** — if the user wants to drop a single document,
  use `doc_write_text` directly. Templates carry input schemas; the
  overhead isn't worth it for a single file.
- **OAuth-only setup** — if the user has the YAML at hand and just
  wants to register a provider, hit the OAuth admin endpoint directly.
  Templates are for "create the provider, create the server-tool, and
  optionally pre-fill secrets" in one go.
