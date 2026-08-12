---
triggers: vault, secret, infisical, api token, api key, credential, password, store a secret, save a secret, generate a secret, provision a credential, rotate a token, {{secret:vault, compose secrets, secret manager, wo speichere ich das token, secret ablegen, PASSWORD-typed, cannot be resolved through a secret reference, reserved for operator configuration, cannot be overwritten by an agent, SecretAccessDenied, hidden setting, requires a project scope, settings vault, no vault configured, kein vault konfiguriert
summary: Reference, inject, and provision secrets — held either in Vancetope's own hidden settings (the default, no setup) or in an external manager (Infisical). Read anywhere via {{secret:vault:<key>}}, inject into compose exec via a secrets: block, and create secrets with vault_secret_generate / vault_secret_set. Also explains the HIDDEN-vs-PASSWORD setting types and the three write rules — read this before reporting any "secret access denied" error as a dead end.
---
# Vault Secrets — reference, inject, provision

Secrets (API tokens, DB passwords, credentials) live in a secret manager bound to
this scope — never in Vance documents in plaintext. You interact with them three
ways.

**There is always a vault.** With no external manager configured, `vault:`
resolves against Vancetope's own `hidden` settings — so
`{{secret:vault:<key>}}` and `vault_secret_generate` work out of the box. Never
say a secret cannot be stored because no vault is set up. A later switch to an
external manager keeps every reference valid; only the values move.

## 1. Reference a secret (read)

Anywhere the `{{secret:…}}` syntax is honoured — REST/HTTP tool templates, SMTP
config, tool headers — reference a vault secret by key:

```
Authorization: Bearer {{secret:vault:my-api-token}}
```

The value is resolved **server-side at call time** and never enters your context.
If the key is missing, it substitutes empty (the call then fails with a 401), it
does not error loudly.

### Which settings you can resolve: HIDDEN, not PASSWORD

Besides `vault:`, a reference can name a **setting** (`{{secret:project:<key>}}`,
`tenant:`, `user:`, or a bare key for the cascade). Settings come in two encrypted
types, and **you** can only resolve one of them:

| Type | Encrypted at rest | You (agent/script) can resolve | Used by |
|---|---|---|---|
| `HIDDEN` | yes | **yes** | secrets a script or a compose task resolves itself |
| `PASSWORD` | yes | no | connectors (SMTP/IMAP, REST and MCP tool packs) and compiled server code |

**A PASSWORD setting is not unusable — it is unusable by you.** A connector
resolves it perfectly well; that is the whole point of the type. So if a tool
authenticates fine but you cannot read its credential, nothing is broken.

Referencing a PASSWORD setting yourself fails with a named error — *"setting X is
PASSWORD-typed and cannot be resolved through a secret reference"*. Do not
"fix" this by asking for the type to be changed: check first whether a connector
already uses the value, in which case it is correct as it is. Only a secret you
genuinely have to resolve in a script or a compose block belongs in `HIDDEN`, and
only a human can change a type.

For `vault:` it depends on what is bound. An external manager has no setting type
in play. The **default** settings-backed vault does resolve settings, and there too
only `HIDDEN` comes out. Either way you write the same reference.

## 2. Inject into a compose `exec` task (env)

A `secrets:` block on an `exec` task maps env-var names to secret references. The
values are injected as environment variables for that command only — kept out of
the persisted state store and masked out of the output log.

```yaml
tasks:
  - type: exec
    secrets:
      DEPLOY_TOKEN: vault:deploy-token   # also project:/tenant:/user: refs
    command: 'curl -H "Authorization: Bearer $DEPLOY_TOKEN" https://…'
```

Injection is **WORK-target only**. Don't copy a secret env var into another shell
variable — only the declared names are kept out of the state store, and only the
exact injected value is masked from the log.

## 3. Provision or store a secret (write)

These two tools are **deferred** — activate with `tool_description` first. Both need
project-scope write permission. They do **not** need an external manager: without
one they write a `hidden` setting (§5).

- **`vault_secret_generate(key, [format], [length])`** — the safe choice.
  Generates the value server-side (`alphanumeric`/`hex`/`uuid`), stores it, and
  returns **only** the reference `vault:<key>` — you never see the value. Use this
  to provision a fresh credential and wire it in via `{{secret:vault:<key>}}`.
- **`vault_secret_set(key, value)`** — stores a value you already have. Note the
  value has, by definition, already passed through your context — if secrecy from
  the model matters, use `vault_secret_generate` instead.

## 4. Pull from a script (`vance.secret`)

Inside a script, resolve a reference to its value server-side — the value lives
only in a local variable, never in env or the state store:

```js
const token = vance.secret('vault:jira-token');   // JS
```
```python
token = vance.secret('vault:jira-token')           # Python (vance.py)
```

Same grammar (`vault:` / `project:` / `tenant:` / `user:` / bare key); returns
`null`/`None` when the key doesn't resolve. Available on
Cortex-run scripts (where `vance.documents` etc. also work). Pulled values are
masked out of a JS string return / Python stdout — but don't echo or persist
them needlessly.

## 5. What you may and may not write

Whether a write lands in an external manager or in a setting depends on the
binding, and you cannot tell from the reference — that is the point. With the
default (settings-backed) vault, `vault_secret_generate` / `vault_secret_set`
write a `hidden` setting at project scope, so the rules below apply to them too.

Installing a credential through `tool_template_apply` or a kit install writes a
setting. Two rules apply to those writes because *you* triggered them, plus the
read-side error for completeness:

| Error says | Meaning | What to do |
|---|---|---|
| *"exists as PASSWORD and cannot be overwritten by an agent"* | a real secret is already there | don't retry. Ask the human to change it, or use a different key |
| *"reserved for operator configuration"* | the key is on the operator deny-list (`ai.provider.*`, `vault.*`) | don't retry, not with another spelling either. LLM provider keys and vault credentials are set by a human in the setting forms |
| *"PASSWORD-typed and cannot be resolved…"* | you are **reading** a secret meant for a connector (§1) | usually nothing — check whether a connector already uses it |

A credential you install this way lands as **PASSWORD**: the connector installed
alongside it resolves the value, you cannot. That is intended and needs no action
from you.

Nothing here can be worked around by choosing a different type or scope: you
cannot set a setting's type, and the deny-list is server configuration, not a
setting. A vault secret (`vault_secret_generate`) is the alternative when you need
to provision something yourself.

## Don't refuse without checking

Never say "I can't store/generate a secret", "I have no way to keep this
credential safe", or "no vault is configured" — there is always one:
`vault_secret_generate` provisions a secret without ever exposing it, and
`{{secret:vault:<key>}}` references it. Configuring an external manager
(Profile/Workspace → *Vault* setting form) is optional.

Equally, don't report a §5 denial as "the system is broken" or retry it in a
loop. Each one is a deliberate boundary with a named next step — state which
one you hit and what the human has to do.
