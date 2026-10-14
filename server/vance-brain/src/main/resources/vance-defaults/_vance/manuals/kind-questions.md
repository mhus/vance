---
triggers: ask several questions, mehrere Fragen, questionnaire, Fragebogen, ask the user, Rückfragen, clarifying questions, Klärungsfragen, questions fence
summary: Ask the user several questions at once — each answered separately, shown as a step-by-step wizard in the chat.
---
# Document kind — `questions`

A `questions` fence asks the user several related questions in one
message. The Web-UI shows it as a wizard: one page per question, a
progress bar, an overview to review all answers before sending. Every
question also offers "good as it is", "later" and "don't know", and a
free-text field. The answers come back as your next user message — a
readable summary ("Region → eu-central-1 · Retention → confirmed") plus
the structured answers for engines that read them.

## When to use it

| Situation | Use |
|---|---|
| 2+ related open points you need decided before you can go on | **`questions` fence** |
| One simple question | plain text (or your engine's ask action) |
| Information the user should read, not answer | plain text / another kind |

Ask few, sharp questions (≤ 7). Put your own best assumption into
`proposal` and use `kind: confirm` — confirming is one click.

## Format — the fence body is YAML

````
```questions
intro: Three points about the archive requirement
questions:
  - key: eu-region
    text: Which region stores the payment records?
    kind: choice
    options: [eu-central-1, eu-west-1]
    context: "must be stored … exclusively within the European Union"
  - key: retention
    text: Correct the retention to 10 years?
    kind: confirm
    proposal: 10 years instead of 5
  - key: owner
    text: Who owns the archive?
```
````

| Field | Required | Meaning |
|---|---|---|
| `questions[].key` | yes | stable, unique within the set (kebab-case) — the answer refers to it |
| `questions[].text` | yes | the question, one sentence |
| `questions[].kind` | no | `choice` (needs ≥ 2 `options`), `confirm` (needs `proposal`), `text` (default) |
| `questions[].options` | for choice | strings, or `{label, description}` |
| `questions[].proposal` | for confirm | your assumption the user confirms or corrects |
| `questions[].context` | no | short quote / location the question is about |
| `questions[].weight` | no | `blocker`, `important` (default), `minor` |
| `intro` | no | one sentence above the questions |
| `id` | no | set id; the client generates one when missing |

Do not add the standard exits ("good as it is", "later", "don't know")
as options — the wizard always offers them.

The reply must CONTAIN the fence verbatim. Validate a draft with
`kind_validate(content=…, kind="questions")` when unsure — duplicate
keys, a choice without options or a missing text are rejected.

## Stored form

As a document (`doc_create_kind(kind="questions")`, YAML) the same body
carries a `$meta` header:

```yaml
$meta:
  kind: questions
questions:
  - key: first
    text: First question?
```

Spec: `specification/public/doc-kind-questions.md`.
