---
triggers: model alias, modell alias, ai.alias, ai.alias.default, default:fast, default:chat, default:analyze, default:code, default:web, default:deep, default:fim, alias auflösen, model alias setzen, welches modell für task, model spec, ai.default.provider, ai.default.model, modell label, task modell, quick model, analyze model
summary: The ai.alias.default.* settings — which model aliases exist (fast, chat, analyze, deep, web, code, fim plus engine/task-specific ones), how they resolve (direct provider > alias setting > default fallback), the value forms, and how they are written per project or tenant-wide.
---
# Model aliases — the `ai.alias.default.*` settings

Recipes reference **aliases** instead of concrete models — `default:fast`,
`default:analyze` — so one configuration works on every tenant, whatever
provider keys it has. Which concrete model an alias points at is a
settings question, and these are the keys.

## Resolution order (per spec, `AiModelResolver`)

For a spec `<prefix>:<rest>`:

1. `prefix` is a provider wire-name (`anthropic`, `openai`, …) → direct.
2. Setting `ai.alias.<prefix>.<rest>` is set → resolve its value
   recursively (the value may itself be an alias or a comma-cascade).
3. `default:` namespace with no alias configured → falls back to
   `ai.default.provider` + `ai.default.model` (the safety net that keeps
   bundled recipes working with zero alias setup).

## The canonical keys

| Key | Points at | Used by |
|---|---|---|
| `ai.alias.default.fast` | a small, cheap, quick model | follow-ups, suggestions, metadata, triage |
| `ai.alias.default.chat` | the model for **interactive chat surfaces** — the highest turn volume in the system; unset means chat recipes fall back to the analyze tier | arthur, eddie, discuss recipes (as a degradation ladder `default:<engine>,default:chat,default:analyze`) |
| `ai.alias.default.analyze` | the strong reasoning model | analysis, planning, judges, deep reads |
| `ai.alias.default.deep` | the strongest available model | heavyweight synthesis, escalation |
| `ai.alias.default.web` | a web/research-capable model | research tasks |
| `ai.alias.default.code` | a coding model | code work, reviews, exec planning |
| `ai.alias.default.fim` | a **completion-trained** model with a `fimTemplate` | follow-up edit mode — unset means chat path; pointing it at a chat model is a hard error by design |

Beyond these, engines and internal tasks may read their own alias —
`ai.alias.default.arthur`, `default:eddie`, `default:ford`, `default:creator`,
`default:follow-up`, `default:document-summary`, `default:how-do-i`,
`default:research`, `default:session-metadata`, … — each overriding the
tier for exactly that task. To find out which alias a recipe expects:
`recipe_describe` shows its `params.model`.

## Value forms

```
ai.alias.default.code = coding-proxy:gpt-5            (instance:model)
ai.alias.default.fast = gemini:gemini-2.5-flash       (protocol default instance)
ai.alias.default.chat = anthropic:claude-haiku-4-5,openai:gpt-4o-mini
                                                       (comma-cascade: first
                                                        configured entry wins)
```

The target may name a provider instance registered in the catalog
(sidecar under `_vance/model/<instance>/`); its credentials decide
whether the call actually works.

## Writing them

Aliases are plain STRING settings — not on the deny list, so `setting_set`
may write them wherever the invoking user is ADMIN: in a project
(scoped to it) or `projectId: '_tenant'` (tenant-wide default, the usual
home). Verify with `setting_get` — it reports which layer holds the value.

Typical request: "in this project, code should run on coding-proxy" →

1. `setting_get` — is `ai.alias.default.code` already set, and where?
2. `setting_set` `ai.alias.default.code` = `coding-proxy:gpt-5`
   (project scope, or `_tenant` when the user wants it everywhere).
3. `setting_get` — confirm key + value + scope.

## Anti-patterns

- Setting `ai.alias.default.fim` to a chat model — it must point at a
  model with a `fimTemplate`, otherwise the follow-up edit mode fails
  closed.
- Guessing which alias a task uses — `recipe_describe` names it.
- Leaving `ai.default.provider` + `ai.default.model` unset on a fresh
  tenant — every unset `default:*` alias needs that safety net.

## Related

- `manual_read('ai-model')` (creator workers only — registering the provider instance an alias points at)
- `manual_read('settings-tools')` — the read/write rules of setting_set
  and setting_get.
- Spec: `specification/public/llm-resource-management.md` §Model-Alias-Resolution.
