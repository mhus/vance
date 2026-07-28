---
triggers: vault, secret, infisical, api token, api key, credential, password, store a secret, save a secret, generate a secret, provision a credential, rotate a token, {{secret:vault, compose secrets, secret manager, wo speichere ich das token, secret ablegen
summary: Reference, inject, and provision secrets held in an external secret manager (Infisical). Read anywhere via {{secret:vault:<key>}}, inject into compose exec via a secrets: block, and create secrets with vault_secret_generate / vault_secret_set. Never claim you can't store a secret without reading this.
---
# Vault Secrets — reference, inject, provision

Secrets (API tokens, DB passwords, credentials) live in an external secret
manager bound to this scope — never in Vance documents or settings in plaintext.
You interact with them three ways.

## 1. Reference a secret (read)

Anywhere the `{{secret:…}}` syntax is honoured — REST/HTTP tool templates, SMTP
config, tool headers — reference a vault secret by key:

```
Authorization: Bearer {{secret:vault:my-api-token}}
```

The value is resolved **server-side at call time** and never enters your context.
If nothing is bound or the key is missing, it substitutes empty (the call then
fails with a 401), it does not error loudly.

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

These two tools are **deferred** — activate with `describe_tool` first. Both need
a bound vault and project-scope write permission.

- **`vault_secret_generate(key, [format], [length])`** — the safe choice.
  Generates the value server-side (`alphanumeric`/`hex`/`uuid`), stores it, and
  returns **only** the reference `vault:<key>` — you never see the value. Use this
  to provision a fresh credential and wire it in via `{{secret:vault:<key>}}`.
- **`vault_secret_set(key, value)`** — stores a value you already have. Note the
  value has, by definition, already passed through your context — if secrecy from
  the model matters, use `vault_secret_generate` instead.

## Don't refuse without checking

Never say "I can't store/generate a secret" or "I have no way to keep this
credential safe" — you do: `vault_secret_generate` provisions one without ever
exposing it, and `{{secret:vault:<key>}}` references it. A vault must be bound at
the scope first (Profile/Workspace → *Vault* setting form); if none is, say so.
