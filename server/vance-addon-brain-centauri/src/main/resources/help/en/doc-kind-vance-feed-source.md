# Feed source

One file under `_vance/config/feeds/<name>.yaml` — the configuration
of a single Centauri feed. The file's name is the name the feed
service manages the source under.

## Form

- **Protocol** — kind of feed (RSS, Atom …).
- **Endpoint** — the feed's URL.
- **API key** — access data as a vault reference
  (`{{secret:vault:…}}`), never plaintext.
- **Enabled** — pauses the feed without deleting it.

Everything the form does not render (reader identity, protocol
extras) stays in the YAML and keeps being used.
