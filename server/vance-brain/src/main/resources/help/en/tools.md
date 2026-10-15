# Server Tools

Tools the brain itself runs — MCP servers, REST APIs, mail accounts,
scripts. Pick a project on the left, or **_tenant** for tenant-wide
defaults. A project tool with the same name overrides the tenant's.

## Connect an MCP server

1. **+** → **MCP server**: a name, the server URL
   (`https://…/mcp`) and how it authenticates.
   - **OAuth login** — servers like Notion, Linear or Atlassian that
     ask you to sign in. Vancetope finds the login server and registers
     itself there; you only need a client ID for servers that do not
     allow self-registration.
   - **None** or a **bearer token** for everything else.
2. Open the tool and adjust the form: transport (HTTP or a local
   process), authentication, TLS, timeouts, default arguments. **JSON**
   shows the raw parameters, including keys the form does not know.
3. **Log in** next to the tool in the list. The browser goes to the
   provider's login and comes back here. From the next chat turn on the
   tools of the server are available.
4. **Test** lists the server's tools as you see them. Switch off tools
   the model should not use.

## Whose account

For OAuth servers the field **Whose account** decides who logs in:

- **Personal** — every user logs in with their own account (here or under
  *Connected accounts*). Users who have not logged in do not see the
  server's tools.
- **One per project** — a project admin logs in once; everybody in the
  project works with that account.
- **One for the tenant** — a tenant admin logs in once for everybody.

With a shared account the provider cannot tell which user triggered an
action.

## Secrets

Do not type tokens or passwords into the form — store them as a setting
and reference them, e.g. `{{secret:tenant:example.token}}`. OAuth tokens
are stored encrypted and refreshed automatically.
