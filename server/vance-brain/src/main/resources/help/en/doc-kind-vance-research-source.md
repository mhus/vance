# Research source

One file under `_vance/config/research/<name>.yaml` — the
configuration of a single Zarniwoop source. The file's name is the
name the research dispatcher uses to pick the source.

## Form

- **Protocol** — the kind of access (`serper`, `tavily`, …). The
  catalog of known protocols grows with the build; an unknown value
  stays as a raw input.
- **Endpoint** — the API's base URL.
- **API key** — as a vault reference (`{{secret:vault:…}}`) or a
  reference to the credential in the setting forms. **Not** in
  plaintext — the file lives in the project.
- **Enabled** — switched off, the source is paused (errors and timing
  keep counting, queries go elsewhere).

The form also renders the fields matching the chosen protocol —
everything it does not render stays in the YAML untouched.
