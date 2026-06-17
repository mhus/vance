---
triggers: inbox_post, Inbox-Item, notify user, decision request, approval request, feedback, ergebnis melden, output to inbox, inbox item type, post to inbox
summary: How to call inbox_post — required params, the 8 InboxItemType enum values, ask-vs-output split, and the per-type payload contract.
---
# Posting to a user's inbox

`inbox_post` drops a structured item into a user's Inbox. Used by
engines and scripts to surface analyses, ask for decisions, request
feedback, or just notify "task done". The Inbox persists; the user
sees the item in the Inbox editor (Web-UI / Foot / Mobile).

## Required fields

```js
vance.tools.call('inbox_post', {
  targetUserId: vance.context.userId,   // recipient
  type: 'OUTPUT_TEXT',                  // strict enum, see below
  title: 'Daily digest 2026-06-17',     // headline
  // optional below:
  body: '... markdown long-form ...',
  criticality: 'NORMAL',
  tags: ['daily', 'digest'],
  payload: { /* type-specific */ },
  documentRef: { id: '6a32...' }        // or { projectId, path }
});
```

| Field | Required | Note |
|---|---|---|
| `targetUserId` | yes | Use `vance.context.userId` for "the running script's owner" (scripts) or the session-owner (engines). |
| `type` | yes | One of the 8 enum values below — **NOT a freetext string**. |
| `title` | yes | One-line, shows in the inbox list. |
| `body` | no | Markdown allowed. Long-form context. |
| `criticality` | no | `LOW` / `NORMAL` / `CRITICAL`. Default `NORMAL`. Drives notification routing (terminal bell, push). |
| `tags` | no | Free-form array, for filtering. |
| `payload` | no | Type-specific structured data — see "Per-type payload" below. |
| `documentRef` | no | `{id}` or `{projectId, path}` — validated against `DocumentService`; the resolved ref lands in `payload.documentRef`. |

## The 8 types — Asks vs. Outputs

Two semantic categories. The split decides whether the calling
process waits for an answer (Asks) or just fires-and-forgets
(Outputs).

**Asks** — `requiresAction: true`, user must answer:

| Type | When to use |
|---|---|
| `APPROVAL` | Yes/No gate — work continues only on approval. |
| `DECISION` | Choose one option from a named set (`payload.options`). |
| `FEEDBACK` | Open-text reply expected (review, comment, correction). |
| `ORDERING` | User reorders items in `payload.items`. |
| `STRUCTURE_EDIT` | Propose a doc-structure edit; user accepts/declines/edits. |

**Outputs** — `requiresAction: false`, informational only:

| Type | When to use |
|---|---|
| `OUTPUT_TEXT` | Plain analysis result, summary, status report. Most common. |
| `OUTPUT_IMAGE` | Image result (Fenchurch); `payload.imageUrl` or `documentRef`. |
| `OUTPUT_DOCUMENT` | A finished document — link via `documentRef`. |

**v1 caveat:** Web-UI fully renders `APPROVAL`, `DECISION`, `FEEDBACK`,
`OUTPUT_TEXT`. The other four are accepted but have less polished UI;
prefer the four well-supported types when in doubt.

## Per-type payload

| Type | Payload shape |
|---|---|
| `APPROVAL` | `{}` (no extra fields). The answer is yes/no. |
| `DECISION` | `{options: [{value, label, description?}, ...], default?: value}`. |
| `FEEDBACK` | `{}` — user replies free-text. |
| `ORDERING` | `{items: [{id, label}, ...]}` — user reorders the list. |
| `STRUCTURE_EDIT` | `{path: "documents/...", proposed: "<new content>", diff?: "..."}`. |
| `OUTPUT_TEXT` | `{}` — body is the content. |
| `OUTPUT_IMAGE` | `{imageUrl: "..."}` **or** `{documentRef}` for an image doc. |
| `OUTPUT_DOCUMENT` | `{documentRef}` (resolved from the top-level field). |

Auto-answer shortcut: `criticality: 'LOW'` + `payload.default: <value>`
on an Ask → the Inbox auto-resolves the item immediately with the
default, without notifying the user. Useful for "would you also do
X?"-style passive suggestions.

## Common patterns

### Inform: a script finished a batch

```js
vance.tools.call('inbox_post', {
  targetUserId: vance.context.userId,
  type: 'OUTPUT_TEXT',
  title: 'Mail-Triage: 5 wichtige Mails',
  body: 'Robins Review, Rechnung Sipgate, ...',
  criticality: 'NORMAL',
  payload: { processed: 20, important: 5, archived: 15 }
});
```

### Ask: approve before sending

```js
vance.tools.call('inbox_post', {
  targetUserId: vance.context.userId,
  type: 'APPROVAL',
  title: 'Send daily-digest mail to team@?',
  body: '12 items aggregated. Preview below.\n\n...',
  criticality: 'NORMAL'
});
```

### Output: finished a document

```js
vance.tools.call('inbox_post', {
  targetUserId: vance.context.userId,
  type: 'OUTPUT_DOCUMENT',
  title: 'Story-Plot fertig: "Der Roboter ohne Schrauben"',
  documentRef: { path: 'documents/story/plot.md' }
});
```

## Common mistakes

- **Freetext `type` (e.g. `'mail.important'`)** — rejected with
  "Unknown inbox item type". The enum is closed.
- **Calling `inbox_post` without `targetUserId`** — required; the
  inbox routes by user. Engines: pass the session-owner. Scripts:
  pass `vance.context.userId`.
- **Using `OUTPUT_TEXT` when you actually want an answer** — that
  type has `requiresAction: false`, so no reply lifecycle. Use
  `FEEDBACK` if you want the user to comment.
- **Stuffing arbitrary keys into the top-level params** — only
  `targetUserId, type, title, body, criticality, tags, payload,
  documentRef` are recognized. Extra data goes inside `payload`.

## See also

- `manual_read('script-document-api')` — wenn das Inbox-Item ein
  Doc referenzieren soll, dieses Manual erklärt die `documents/`-
  Pfad-Convention.
