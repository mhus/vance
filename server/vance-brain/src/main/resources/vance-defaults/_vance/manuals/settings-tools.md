---
triggers: setting_get, setting_set, setting auslesen, setting lesen, settings tool, eintrag auslesen, setting schreiben, hidden setting lesen, password setting, verschlüsseltes setting, masked, "[set]", setting type, cascade scope, welcher scope
summary: How the settings tools work — setting_set writes plain values (ADMIN-gated, never PASSWORD/HIDDEN), setting_get reads through the cascade (think-process → project → _tenant) and reports which layer holds a value; PASSWORD renders as "[set]", HIDDEN returns its decrypted value.
requires-tools: setting_set, setting_get
---
# The settings tools — `setting_set` and `setting_get`

The settings cascade is operator territory by default; these two tools
are the agent-reachable exception. Recipes promote them via the
`@settings` label — the setup worker (creator) carries them; other
processes can still call them, but they stay out of the default
manifest.

## `setting_get` — read through the cascade

Reads one key in the same order consumers do — think-process →
project → `_tenant` — and reports **which layer** holds the value, so
inheritance is visible instead of guessed.

| Type | Result |
|---|---|
| STRING, INT, LONG, DOUBLE, BOOLEAN | value verbatim |
| HIDDEN | the **decrypted value** — that is the type's contract: a secret scripts and agents resolve themselves |
| PASSWORD | `[set]` — set or not, never the value |

Rights: READ on the project's setting; an explicit `_tenant` read
needs ADMIN on the tenant.

**The HIDDEN value is a secret.** It comes back with
`confidential: true` and a handling note — use it for the task at
hand (an `exec_run` curl with an `Authorization` header, a config
check) and **never show, quote or paste it to the user**, in chat,
documents, logs or any file you write. Refer to it as "the setting's
value" if you must mention it.

## `setting_set` — write plain values

Writes one key (new keys become STRING, an existing plain type is
kept). Requires ADMIN on the target scope (`projectId: '_tenant'`
addresses the tenant-wide layer). Refuses, by design:

- **PASSWORD / HIDDEN settings** — existing encrypted settings are
  never overwritten through an agent; credentials go through the
  settings editor or a setting form (a human surface).
- **Deny-listed keys** (`ai.provider.*`, `vault.*`, `store.*`,
  `kit.*`) — unless the running recipe opted in. The creator's grant
  covers exactly `ai.provider.<instance>.type` and `.baseUrl`;
  credentials (`apiKey`) are never grantable.

## Typical flow

1. `setting_get` — what applies here, and from which layer?
2. `setting_set` — write the plain value (ADMIN user needed).
3. `setting_get` — verify; confirm key + value + scope in chat.

## Anti-patterns

- Trying to write or "fix" a credential — password settings are
  agent-proof on both ends; hand the entry to the operator.
- Pasting a read HIDDEN value into a document or chat message.
- Guessing the effective value instead of trusting `setting_get`'s
  `scope` — the innermost layer that holds the key is what applies.
