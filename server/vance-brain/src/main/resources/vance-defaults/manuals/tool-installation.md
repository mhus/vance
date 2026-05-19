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
1. user: "set up Jira"

2. agent:
   find_tools(query="tool_template")
   → confirms the three tools exist

3. agent:
   invoke_tool(tool_template_list, {})
   → templates: [{name: "jira", title: "Atlassian Jira", ...}, ...]

4. agent:
   invoke_tool(tool_template_describe, {name: "jira"})
   → inputs: [{name: "clientId", required: true, ...},
              {name: "clientSecret", type: "password", target: "setting", ...}]
   → postInstall: {kind: "oauth-connect", provider: "atlassian", ...}

5. agent → user (ASK_USER):
   "Brauche zwei Werte aus deinem Atlassian-Developer-Console:
   - Client ID
   - Client Secret (wird verschlüsselt in den Tenant-Settings abgelegt)
   Beide findest du unter https://developer.atlassian.com/console → deine App → Settings."

6. user provides values

7. agent:
   invoke_tool(tool_template_apply, {
     name: "jira",
     projectId: "_tenant",
     inputs: { clientId: "...", clientSecret: "..." }
   })
   → applied: true, postInstall: {kind: "oauth-connect", provider: "atlassian", ...}

8. agent → user (ANSWER):
   "Jira-Provider ist konfiguriert. Letzter Schritt: öffne Connected Accounts
   in der Web-UI (http://localhost:9900/connected-accounts.html) und klick
   bei 'atlassian' auf Connect. Danach steht das Jira-Tool zur Verfügung."
```

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

Without a `postInstall`, the template is fully set up — confirm to
the user and move on.

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
