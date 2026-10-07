# Wizard

One file under `_vance/wizards/<name>.yaml` — a form that generates a
prompt. The file's name is the name the wizard appears under
everywhere (chat wizard tab, `POST /wizards/<name>/render`).

## Schema

- `title` / `description` — localized (`de:`/`en:`) or a plain
  string (= `en`). Required.
- `icon`, `category` — display in the wizard tab.
- `fields` — **at least one.** The shared field vocabulary: `name`,
  `type` (string, textarea, number, choice, …), `label`, `help`,
  `required`, type extras like `rows` or `options`.
- `promptTemplate` — **required**, Pebble. Renders the field values,
  e.g. `{{ subject }}`.
- `validatorPrompt` — optional; LLM validation of the input.
- `suggestedFollowUps` — optional follow-up suggestions.
- `availableIn` — glob list of projects; `!pattern` excludes.

## Pitfalls

- A missing `promptTemplate`, empty `fields` or broken Pebble makes
  the wizard **silently vanish** — the server reports it on save.
- Same name wins from the inside: `_user` over project over `_vance`
  over classpath.
