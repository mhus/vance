---
triggers: vance.settings, vance.settings.get, settings cascade, read setting, project setting, settingsForm, setting-form, config from settings, kit config
summary: How a script reads project/process/tenant settings via vance.settings.* — typed accessors, cascade scope, fall-back defaults. Pairs with Setting-Forms for user-facing configuration.
---
# Reading settings from a script — `vance.settings.*`

Use this to make a script **configurable without editing the source**:
the user (or a Kit) puts values into the settings cascade, the script
reads them at runtime.

```js
const pack    = vance.settings.get('mail.pack');                       // String|null
const folder  = vance.settings.get('mail.inboxFolder', 'INBOX');       // String with default
const maxN    = vance.settings.getInt('mail.maxPerRun', 5);            // int with default
const dryRun  = vance.settings.getBoolean('mail.dryRun', false);       // bool with default
const ratio   = vance.settings.getDouble('mail.threshold', 0.7);
const bigN    = vance.settings.getLong('mail.byteLimit', 1048576);
```

## Cascade

Lookup order, innermost-first:

1. **Think-process** scope (per-run overrides, set by recipe/Slart)
2. **Project** scope (the typical kit-default home)
3. **Tenant default project** (`_tenant`) — the fall-through layer

**User-scope is deliberately excluded.** That keeps tenant/project-
controlled values (API keys, model aliases, integration URLs) safe
from per-user overrides. If you need per-user state, use
`vance.documents.read('_user_<id>/...')` or expose a Setting-Form
with `defaultScope: user`.

The cascade is read-only from scripts — there is no
`vance.settings.set(...)`. Settings are written by:

- Kit install (Kit `settings/<key>.yaml` files land in project-scope)
- Setting-Forms (user submit via Web-UI Workspace-Editor tab)
- Admin REST (`POST /brain/{tenant}/settings`)
- `SettingService` from server-side Java code

## Typed accessors

| Method | Returns | Behaviour |
|---|---|---|
| `get(key)` | `String \| null` | Raw cascade value, or `null` when no scope defines it. |
| `get(key, default)` | `String` | Default when value is `null` or blank. |
| `getInt(key, default)` | `int` | Parses int; default on missing/unparseable. |
| `getLong(key, default)` | `long` | Same, for long. |
| `getDouble(key, default)` | `double` | Same, for double. |
| `getBoolean(key, default)` | `boolean` | Accepts `true \| 1 \| yes \| on` (case-insensitive); anything else parses as false. |

Password settings are **not exposed** here — `SettingService` filters
them out of the cascade lookup. Use `setting_get_password`-style tools
(when available) or resolver-syntax inside tool configs, never
`vance.settings.get('...password')` in a script.

## Pattern: kit-default + scheduler-override + sane fallback

Three-layer config in a single line:

```js
const folder = (vance.params && vance.params.folder)
    || vance.settings.get('mail.inboxFolder', 'INBOX');
```

- **`vance.params.folder`** — set by the scheduler (`params:` block in
  the YAML) or by the caller of `hactar-run`. Highest precedence —
  per-run override.
- **`vance.settings.get('mail.inboxFolder')`** — kit default, written
  to project-scope by the Kit's `settings/mail.inboxFolder.yaml`. The
  user can change this via a Setting-Form.
- **Inline `'INBOX'` default** — the absolute fallback if neither
  setting nor param exists. Keeps the script runnable even with
  zero configuration.

## Errors

Same `Error` surface as `vance.documents.*`:

| Cause | Message contains |
|---|---|
| Empty key | `"vance.settings: key must not be empty"` |
| Script without tenant scope (trigger-scoped sandbox) | `"vance.settings requires a tenant-scoped run"` |

`vance.settings` itself is `null` when the script was launched
without a `SettingService` injected — older trigger-scoped sandboxes
or unit-test stubs. JS-access then throws a TypeError (`Cannot read
property 'get' of null`).

## See also

- `manual_read('scripts')` — the general script surface.
- Spec: `specification/setting-forms.md` — how a Kit ships a UI form
  that writes to the same settings the script reads.
- Spec: `specification/kits.md` §3 — Kit `settings/` directory layout.
