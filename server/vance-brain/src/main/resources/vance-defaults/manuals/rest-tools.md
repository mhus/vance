---
triggers: REST, REST API, HTTP, OpenAPI, Swagger, rest_api, API tool, generated tools, third-party API, integration, custom endpoint
summary: How to wire a REST-API tool from an OpenAPI spec — emits one typed tool per operation, supports key auth / OAuth / mutual-TLS.
---
# REST API tools

How to wire a server tool that talks to any REST API — public,
key-authed, OAuth-protected, or behind a mutual-TLS gateway. Vance's
`rest_api` factory reads an OpenAPI spec and emits one runnable tool
per operation, schemas and all. You write a few lines of YAML, the
LLM gets typed tools.

## When to use `rest_api`

- The remote service publishes an OpenAPI 3.x spec (Atlassian, GitHub,
  Stripe, internal services with springdoc, …).
- A handful of curated REST endpoints you want to expose without
  hand-writing tool schemas.
- A service behind OAuth where the connecting user's token rides the
  request as a Bearer header — same pattern as MCP tools, just without
  the MCP-Server middleman.

Choose `mcp_server` instead when the service publishes an MCP endpoint
(`type: mcp_server`, see [mcp-tools.md] when it lands) — MCP gives you
streaming notifications and server-driven tool discovery. For a plain
request/response REST API, `rest_api` is simpler and one network hop
shorter.

## Minimal config

```yaml
type: rest_api
description: "GitHub Issues — list, read, create issues in a repo."
labels: [github, external]
parameters:
  specUrl: "https://api.github.com/openapi.json"
  baseUrl: "https://api.github.com"
  auth:
    type: bearer
    token: "{{secret:user:oauth.github.access_token}}"
  include:
    - "issues/list-for-repo"
    - "issues/get"
    - "issues/create"
  timeoutSeconds: 30
```

`include` is a list of operationIds (the OpenAPI `operationId` field).
`*` is a glob, so `"issues/*"` works too. Without `include`, every
operation in the spec is exposed — usually too much for a real API.

Tool names land as `<pack-name>__<operationId>` so an LLM sees
`github_issues__list-for-repo`.

## Full parameter reference

```yaml
parameters:
  # Pick ONE source of the OpenAPI spec:
  specUrl: "https://api.example.com/openapi.json"   # fetched at materialise time
  # OR
  specInline: |                                      # inline JSON / YAML
    { "openapi": "3.0.0", "paths": { ... } }

  # Override the spec's declared server (the spec's "servers" section is
  # often the wrong URL for production use — Atlassian's spec says
  # "your-domain.atlassian.net" but real OAuth-3LO calls go through
  # "api.atlassian.com/ex/jira/{cloudId}"). baseUrl wins.
  baseUrl: "https://api.example.com"

  # Per-tenant identifiers in the URL — template against user settings.
  # Resolved at call time, every call gets the connecting user's value.
  # Useful for Slack workspace IDs, Atlassian cloudIds, etc.
  # Example: baseUrl: "https://api.atlassian.com/ex/jira/{{secret:user:oauth.atlassian.cloud_id}}"

  auth:
    type: bearer                                    # bearer | basic | apiKey | none
    token: "{{secret:user:oauth.example.access_token}}"

    # type: basic:
    # user: "{{secret:tenant:example.api.user}}"
    # password: "{{secret:tenant:example.api.password}}"

    # type: apiKey:
    # in: header                                    # header | query
    # name: "X-API-Key"
    # value: "{{secret:tenant:example.api.key}}"

  # TLS controls — only for non-public endpoints.
  tls:
    skipVerification: false                         # NEVER true in production
    trustedCaPemPath: "/etc/vance/example-ca.pem"

  # operationId filters. Globs work. Without include, all ops are exposed.
  include: ["search.*", "user/get*"]
  exclude: ["admin_*", "*/delete"]

  # Default labels per HTTP verb (drive the @write / @side-effect filter
  # Eddie's recipe applies). Defaults shown — override per-pack if needed.
  methodLabels:
    GET:    [read-only]
    POST:   [write, side-effect]
    PUT:    [write, side-effect]
    PATCH:  [write, side-effect]
    DELETE: [write, side-effect]

  # Per-operation label overrides — for endpoints where the HTTP verb
  # lies. POST /search is conceptually read-only despite being POST;
  # without an override it would default to [write, side-effect] and
  # land in the deferred bucket of conversational recipes (Eddie).
  labelOverrides:
    searchAndReconsileIssuesUsingJqlPost: [read-only]

  # Per-operation deferred override — force a tool into the discovery
  # block even when its label says it should be primary, or vice versa.
  deferredOverrides:
    createIssue: false

  timeoutSeconds: 30
```

## Recipe: OAuth-protected API (the common case)

You've already done the OAuth setup (see `oauth-tools.md`); the user
has connected their account. Now expose the API's REST surface:

```yaml
type: rest_api
description: "<one short line — what this pack offers>"
labels: [<service>, external, rest]
primary: false                                       # 99% of cases — let find_tools handle it
defaultDeferred: false
parameters:
  specUrl: "<official-openapi-spec-url>"
  baseUrl: "<api-base-url-with-templating>"
  auth:
    type: bearer
    token: "{{secret:user:oauth.<providerId>.access_token}}"
  include:
    - "<operationId-1>"
    - "<operationId-2>"
  labelOverrides:
    "<search-like-POST-id>": [read-only]            # if you have any
  timeoutSeconds: 30
promptHint: |
  ## <Service name>
  Discovery: `find_tools(query="<service>")`.
  Call via: `invoke_tool({name:"<pack>__<op>", params:{...}})`.
  <Pack-specific quirks — e.g. "the cloudId is auto-injected, don't
  set it yourself", "this API rate-limits at 60 req/min", etc.>
```

The `promptHint` lands in the system prompt only when this pack is
reachable for the current user. Use it for "do this, not that" notes
that aren't obvious from the operation descriptions.

## Auto-refresh

Identical to MCP tools — the `OAuthTokenRefresher` reads
`oauth.<providerId>.expires_at` and refreshes 60 s before expiry. You
write `{{secret:user:oauth.<provider>.access_token}}` exactly once, the
plumbing handles the rest.

If the refresh fails (refresh token revoked, scope changed, app
deleted at the provider), the tool call surfaces an
`OAuthExpiredException` — the chat UI will show the user a clear
"reconnect <provider>" hint. You don't need to handle this.

## What you control vs what the spec controls

| You control | The OpenAPI spec controls |
|---|---|
| Which operations are exposed (`include`/`exclude`) | The parameter schema (path, query, body) |
| The tool name prefix (`<packName>__<op>`) | The operation summary / description |
| Default labels per HTTP verb | Which path-parameters exist |
| Per-op label overrides | The request/response shape |
| The base URL (path goes on top) | The relative path |

Schemas come straight from the spec — Vance doesn't try to "improve"
them. If the spec is missing schemas (rare), the operation surfaces
as a black-box tool with `additionalProperties: true`.

## Diagnostics

When a REST tool errors, the actual HTTP status + first 200 chars of
the response body land in the tool's error result. For deeper
debugging, the brain logs the outgoing request + response at
`TRACE` (`logging.level.de.mhus.vance.toolpack.rest=TRACE`). Don't
log Bearer tokens — the resolver redacts them in trace lines.
