# Area: Wizards

The wizard definitions under `_vance/wizards/` — **one file per form**
that generates a prompt. The **only area with a user layer**: the
cascade is `project → _user_<login> → _vance → classpath`, and the
panel's user scope is exactly the `_user_` tier — this is where you
build **your own** wizards without touching the tenant.

## Raw text, deliberately

A wizard is localized `title`/`description`, a list of fields (the
shared form-field vocabulary), a Pebble `promptTemplate` and
optionally `validatorPrompt` and `suggestedFollowUps`. Raw editor
with YAML highlighting.

## What the loader demands

- `title` and `description` — localized (or a plain string, which
  counts as `en`).
- **at least one** field in `fields`.
- `promptTemplate` — required, Pebble-compiling.
- `availableIn` — optional glob list; `!pattern` excludes.

A wizard that fails any of this **silently vanishes from every
surface** (WARN in the log) — the server reports the same on save.

## Name cascade

Same name wins from the inside: your `_user_` wizard shadows the
tenant's, the project wizard shadows both.
