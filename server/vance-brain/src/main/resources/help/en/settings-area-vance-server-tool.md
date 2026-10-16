# Area: Server tools

The tools the brain itself runs — MCP servers, REST APIs, mail
accounts, scripts — under `_vance/server-tools/`, **one file per tool**.
The file name is the tool name.

## Which tools you see

Tools resolve through a cascade: **project → tenant → built-in**. The
list shows the tools of the selected scope first, then — marked with
their origin — the ones it only inherits:

- **Tenant** — defined in the tenant scope, valid in every project.
- **Built-in** — shipped with the server.

An inherited tool is not edited in place. **Override here** copies it
into this scope; from then on the copy wins here, the original stays
untouched for everybody else. Deleting the copy brings the inherited
tool back. To hide a tool in one project, override it and switch
**Enabled** off.

## Adding a tool

**Add** asks for a snake_case name (`notion`, `jira_rest`). The new tool
starts as a **disabled** MCP server: fill in the server, then enable it.
Any other type is chosen in the form.

Server tools exist for the tenant and for projects — there is no
personal layer.
