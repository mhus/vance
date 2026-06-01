---
triggers: OAuth, Slack, Jira, Atlassian, Google, Gmail, GitHub, Microsoft, connect, verbinde, Authentifizierung, Token, Workspace integration, third-party API
summary: How to wire a tool against an OAuth-protected API (Slack, Jira, Google, GitHub, …) using the connecting user's token.
---
# OAuth-backed tools

How to wire a tool that talks to Slack, Jira/Atlassian, Google
Workspace, GitHub, or any other OAuth-protected API. Server tools you
create here use the connecting user's tokens automatically; refresh
is transparent.

## What you can and can't do

| Action | Who does it |
|---|---|
| Register the OAuth app at the provider (Slack/Google/…) | **Tenant admin** in the provider's developer console |
| Configure the provider in Vance (YAML + client secret) | **Tenant admin** in Web-UI „OAuth Providers" |
| Connect the user's account (browser OAuth dance) | **The user** in Web-UI „Connected Accounts" |
| Create a server tool that uses an OAuth-backed endpoint | **You (model)** — see below |
| Refresh the access token when it expires | **Automatic** — `OAuthTokenRefresher` |

You can write `server-tools/<name>.yaml` documents that reference
`{{secret:user:oauth.<providerId>.access_token}}` in their auth
config. The token plumbing — fetch, refresh, persist — happens for
you. If the user hasn't connected the provider yet, your tool calls
will fail with a clear "user must connect provider X" message; tell
them to open Web-UI → Connected Accounts.

## The resolver syntax

| Form | Where the value comes from |
|---|---|
| `{{secret:<key>}}` | Cascade: think-process → project → `_tenant` |
| `{{secret:user:<key>}}` | The connecting user's PASSWORD setting |
| `{{secret:user:oauth.<providerId>.access_token}}` | OAuth access token (auto-refresh) |
| `{{secret:tenant:<key>}}` | Tenant-scope PASSWORD setting |
| `{{secret:project:<key>}}` | Current-project PASSWORD setting |

Use the OAuth form only with the literal pattern
`oauth.<providerId>.access_token` — only this triggers the refresh
path. Other user-scope secrets (`oauth.<id>.refresh_token`,
`oauth.<id>.scopes`) fall through to a plain user-setting read; you
almost never want those.

## Recipe 1: MCP server behind a user-token

The cleanest case — a remote MCP server speaks Slack/Jira/etc., you
just hand it the Bearer token. Write a `server-tools/<name>.yaml`
like this:

```yaml
type: mcp_server
description: "Slack MCP — read channels, post messages, search history."
labels: [slack, external]
parameters:
  transport: HTTP
  url: "https://slack-mcp.example.com/mcp"
  auth:
    type: bearer
    token: "{{secret:user:oauth.slack.access_token}}"
```

What happens on the next user turn:

1. The engine calls the tool.
2. The McpToolPackFactory builds the HTTP request.
3. SettingsSecretResolver sees `{{secret:user:oauth.slack.access_token}}`,
   asks OAuthTokenRefresher for the token.
4. Refresher reads `oauth.slack.expires_at`. If still valid (>60s
   left): return the cached access token. If close to expiry:
   refresh, persist new tokens, return the fresh one. Per-`(tenant,
   user, slack)` lock makes sure ten parallel tool calls only refresh
   once.
5. The Slack-MCP server gets the bearer; the Slack API call runs in
   the user's name.

## Recipe 2: REST-API tool with bearer auth

Same idea, generic-REST instead of MCP:

```yaml
type: rest_api
description: "GitHub REST — list user's repos, open issues, etc."
labels: [github, external]
parameters:
  specUrl: "https://raw.githubusercontent.com/github/rest-api-description/main/descriptions/api.github.com/api.github.com.json"
  include: ["repos/*", "issues/*"]
  auth:
    type: bearer
    token: "{{secret:user:oauth.github.access_token}}"
```

(`type: rest_api` builds many sub-tools from one OpenAPI spec — the
`include:` filter is mandatory unless you really want hundreds of
LLM-visible sub-tools. See the `rest_api` factory's
`parametersSchema()` if you need details.)

## When the user hasn't connected yet

If the user hasn't completed the OAuth dance for `<providerId>`:

- `oauth.<providerId>.access_token` is null.
- The refresher tries to refresh — but there's also no refresh token.
- It throws `OAuthExpiredException` with message:
  `"no refresh token stored — user must reconnect provider 'slack'"`
- The exception propagates up; the tool call fails with that message.

What you do: surface the message to the user, with a hint:
> *"I can't reach Slack — your Slack account isn't connected. Open
> Web-UI → Connected Accounts → Connect Slack, then ask me again."*

Don't loop, don't retry, don't try to do the OAuth dance yourself —
the connect step requires a browser session you don't have.

## When the token's expired and refresh fails

Same exception, different message:
`"provider rejected refresh — user must reconnect: invalid_grant"`

The user revoked the app in the provider's UI, or the refresh token
itself expired. Same response from you: ask them to reconnect.

## Provider IDs

Provider IDs come from the tenant admin's setup. Standard names:

| `<providerId>` | typeId behind it | Used for |
|---|---|---|
| `slack` | `slack` | Slack v2 (user tokens) |
| `atlassian` | `atlassian` | Jira / Confluence |
| `google` | `google` | Google Workspace (Drive, Calendar, Mail, …) |
| `github` | `generic-oauth2` | GitHub Classic OAuth |
| `keycloak-<name>` / `okta` / `entra` | `oidc` | Tenant's SSO IdP |

The tenant can rename or alias them — what you see in
`{{secret:user:oauth.<here>.access_token}}` is whatever the tenant
called the provider config (Web-UI „OAuth Providers"). When in doubt,
list available providers via the user's „Connected Accounts" page.

## Things to remember

- **Always `user:` scope** for OAuth tokens — they're personal.
  `tenant:` or `project:` would be wrong (and won't trigger refresh).
- **Don't put credentials in the YAML body.** `clientId` / `clientSecret`
  of the OAuth app live at the provider-config level; the user's
  tokens live in user settings. You never write or read either of
  those directly.
- **`labels:` matter.** Tag tools that need external providers
  (`labels: [slack, external]`) so recipes can include/exclude them
  cleanly with `@external`, `@slack`, etc.
- **`description:` is what the LLM sees** when picking the tool —
  write it as you'd explain it to yourself.
- **One server tool per logical surface.** Don't lump Slack + Jira
  into a single tool just because both are external; pick descriptive
  per-provider names.
