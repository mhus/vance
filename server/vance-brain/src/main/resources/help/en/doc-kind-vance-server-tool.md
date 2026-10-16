# Server Tool

A tool the brain itself runs — an MCP server, a REST API, a mail
account, a script. The document you have open *is* the tool: one file
under `_vance/server-tools/<name>.yaml`, the file name is the tool name.
A document elsewhere is a draft; the banner at the top says so.

## Connect an MCP server

1. **Type** `mcp_server`, then the server form: transport (HTTP or a
   local process), the server URL (`https://…/mcp`) and how it
   authenticates.
   - **OAuth login** — servers like Notion, Linear or Atlassian that
     ask you to sign in. Vancetope finds the login server and registers
     itself there; you only need a client ID for servers that do not
     allow self-registration.
   - **None** or a **bearer token** for everything else.
   **JSON** shows the raw parameters, including keys the form does not
   know.
2. Switch **Enabled** on and **Apply**. A new tool starts disabled, so a
   half-configured server never reaches the model.
3. **Log in** in the Login block. The browser goes to the provider's
   login and comes back here. From the next chat turn on the tools of
   the server are available.
4. **Test** lists the server's tools as you see them. Switch off tools
   the model should not use, then apply again.

Login and test work on the **saved** tool — apply your changes first.

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

## Other types

Every other type takes its parameters as JSON; the parameter schema of
the type is shown below the editor. **Pack** sets defaults for tools that
expand into many sub-tools (REST API, MCP server): deferred loading and
switched-off sub-tools. **Labels** let recipes address a group of tools;
the **prompt hint** is added to the system prompt on turns where the tool
is reachable.

## Secrets

Do not type tokens or passwords into the form — store them as a setting
and reference them, e.g. `{{secret:tenant:example.token}}`. OAuth tokens
are stored encrypted and refreshed automatically.
