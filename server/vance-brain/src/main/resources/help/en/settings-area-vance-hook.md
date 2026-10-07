# Area: Hooks

The UrsaHooks event hooks under `_vance/hooks/` — **one file per
hook**, and the **event is part of the path**:
`_vance/hooks/<event>/<name>.yaml`. The registry lists per event; a
hook only exists under a known event.

## Names carry structure

The add dialog takes `event/hook-name` — e.g.
`process.completed/notify-owner`. Event and hook name are validated:
the event against the seven known wire names (`process.completed`,
`process.failed`, `inbox.item.created`, `session.suspended`,
`session.resumed`, `insight.saved`, `relation.created`), the hook
name against the loader grammar (lowercase, `_` and `-`, max 64
chars).

## Raw text, deliberately

A hook body is **exactly one TriggerAction** — `recipe:`, `script:`
or `workflow:` — plus lifecycle keys (`description`, `enabled`,
`timeout`, `tags`). No form; raw editor.

## Pitfalls

- The legacy schema (`type: js|llm`, `prompt:`/`model:`) is
  **refused** — the server reports on save what the loader would
  otherwise silently skip.
- Hooks **register with project activation**: the area writes the
  document, the registry loads it on the project's bootstrap or
  refresh.
