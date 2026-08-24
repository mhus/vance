---
triggers: inbox, was liegt an, meine inbox, was ist offen, inbox thread, decision
  request, approval pending, thread lesen, beitrag, clarification, inbox aufräumen,
  als gelesen markieren, archivieren, offene entscheidungen, what is waiting,
  open decisions, pending approval, mark as read, delegieren, weitergeben,
  zuweisen, delegate, hand over, reassign
summary: Reading the Vancetope inbox and contributing to a thread — what the
  inbox_* and thread_* tools do, and why none of them answers a request.
requires-tools: inbox_list, thread_get
---

# The user's inbox, and the threads in it

The inbox is where people and agents put matters in front of someone: a decision
to make, an approval to give, a result to look at. **It is not email and not a
chat.** Each entry is one matter heading for at most one decision, and then it
ends — a side question becomes its own new thread, never a turn in this one.

Two families, because these are two different things:

| | |
|---|---|
| `inbox_*` | **the queue** — what is waiting on you, and your state on it |
| `thread_*` | **one matter** — its question, its clarification, who takes part |

Three things are tracked independently. Confusing them is the most common
mistake here:

| Axis | Meaning | Changed by |
|---|---|---|
| `read` | have *you* seen it | `inbox_mark_read` — never by reading |
| `answered` | has the question been settled | **a person only** |
| `archived` | is it off the list | `inbox_archive` |

## When to use these

- The user asks what is waiting, open, or undecided.
- You posted a request earlier (`inbox_post`) and want to know whether it was
  clarified or answered.
- You have information that would help someone decide an open request.

## The tools

| Call | What it does |
|---|---|
| `inbox_list({ onlyAsks: true })` | What is waiting on you, plus unread counts. The only source of thread ids. |
| `thread_get({ threadId: 'x' })` | One matter and its contributions, paginated. Changes nothing. |
| `thread_message_add({ threadId: 'x', body: '…' })` | Add a contribution. Does **not** answer. |
| `inbox_mark_read({ threadIds: ['x'] })` | Clear the unread mark on named threads. |
| `inbox_archive({ threadIds: ['x','y'] })` | Take settled matters off the list. Refusals appear in `skipped`. |
| `thread_delegate({ threadId: 'x', toUserId: 'robin' })` | Hand a matter to the person who should decide it. |
| `inbox_post({ … })` | Put something in front of someone. See `manual_read('inbox-post')`. |

## What none of them does

**None of them answers a request.** There is no answer tool, no approve tool, no
decide tool — not hidden, not deferred, absent. An `APPROVAL` or `DECISION` exists
because a *person's* judgement was wanted, and answering it can execute something
on the server (granting a permission, for one). If you could answer, the agent
that asked could answer itself, and the request would have been pointless.

So when you find an open ask, your move is one of these:

- report it to the user and let them decide,
- add what you know with `thread_message_add` so their decision is easier,
- or, if the user says it belongs to somebody else, `thread_delegate` it. That
  **routes** the decision without making it: the thread stays open and waits on
  the new assignee. Only ever name a person the user named — putting a matter on
  someone's desk spends their attention.

**Reading is not answering, and reading is not marking read.** `thread_get`
leaves every axis untouched. Only call `inbox_mark_read` when the user asked you
to clear or mark something — the unread count is their alarm, not your
bookkeeping.

## A typical run

```
inbox_list({ onlyAsks: true })
  → threads: [ { threadId: 'a1b2…', title: 'Deploy release 0.4?', requiresAction: true } ]

thread_get({ threadId: 'a1b2…' })
  → body: 'Staging is green since…', messages: [ … ]

thread_message_add({ threadId: 'a1b2…',
                     body: 'Checked: migration 2026-08-12_001 ran on staging, no drift.' })
```

Then tell the user the decision is theirs. And when clearing up afterwards:

```
inbox_archive({ threadIds: ['c3d4…', 'e5f6…', 'g7h8…'] })
  → archived: ['c3d4…', 'e5f6…'], skipped: [{ threadId: 'g7h8…', reason: 'open request …' }]
```

## Common mistakes

- **Inventing an answer tool** (`inbox_answer`, `inbox_approve`, `thread_reply`).
  None exists. Say the decision belongs to a person.
- **Saying you cannot see the inbox.** You can — `inbox_list`. Never claim you
  have no access to someone's mail; it is not mail.
- **Treating a thread like a chat.** It is one matter. A new question is a new
  thread via `inbox_post`, not another contribution here.
- **`inbox_post` for a follow-up on the same matter.** Use
  `thread_message_add` — a second thread splits the discussion and the deciding
  person will not know which answer belongs where.
- **Marking read after your own reading.** Do not tidy up; you would delete an
  alarm the user has not seen.
- **Expecting a team inbox.** These tools read the inbox of the process owner,
  nobody else's.
- **Archiving an open ask.** Refused on purpose: a process may be blocked on that
  decision. Add a contribution saying why it is moot instead. In a batch the
  refusal is per thread — read `skipped`, do not assume the whole call worked.
- **Delegating instead of reporting.** Handing a matter on is not the same as
  telling the user about it. Delegate when they said whose it is; otherwise say
  what you found.
- **Guessing a recipient.** A login you inferred is a matter landing on the wrong
  desk. If the user did not name someone, ask.

## See also

- `manual_read('inbox-post')` — creating an item: the eight types, the payload
  contract, ask-versus-output.
