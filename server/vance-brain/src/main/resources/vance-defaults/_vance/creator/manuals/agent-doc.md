---
audience: creator
triggers: agent.md, agent doc, agent notes, agentDocument, project instructions, standing instructions, projektanweisung, projektregeln, konventionen festhalten, jeder agent soll wissen, immer beachten, dauerhaft mitgeben, tell every agent, CLAUDE.md equivalent, house rules, projekt-spielregeln
summary: How the per-project agent.md works — a Markdown document at the project root that is injected into the system prompt of every think process in that project. Where it lives, what belongs in it, what does not, and how a recipe switches it off.
requires-tools: doc_write
---
# The project's `agent.md`

`agent.md` holds the **standing instructions for every agent working in
this project** — conventions, vocabulary, house rules. It is injected
into the system prompt automatically; nobody has to read or mention it.
This manual is about the *server-side* document. One-off facts and
per-user preferences are a different mechanism (see the end).

## Where it lives

`agent.md` in the **project root** — not under `_vance/`, which is the
reserved config namespace and needs ADMIN to write.

Lookup is the usual document cascade, **first hit wins, no merge**:

1. `agent.md` in the project
2. `agent.md` in the `_vance` project (tenant-wide)

So a project that writes its own `agent.md` *replaces* the tenant-wide
one rather than adding to it. If the tenant version carries rules that
must survive, copy the relevant lines over.

## Who reads it

Every think process that composes the memory block — the chats
(`arthur`, `eddie`), Ford workers, Frankie workers, Trillian. It arrives
as a `## Agent Notes (agent.md)` section in a system message, ahead of
the conversation.

Single-shot helper calls (discovery, follow-up suggestions, title
generation) do **not** see it — they have no process at all.

## What belongs in it

- Project conventions: naming, folder layout, which format a report has.
- Vocabulary: what an internal term means in *this* project.
- Hard rules: "`export/` is published to customers — never write drafts
  there", "always ask before deleting a record".
- Who the work is for: audience, tone, language of the deliverables.

## What does not belong in it

- **Secrets.** The body goes to the model. Use a vault reference —
  `manual_read('vault-secrets')`.
- **One-off facts** ("the deadline moved to Friday"). Those belong in
  memory, not in a document that every process pays for.
- **How-to knowledge for a capability.** That is a manual or a skill;
  they load on demand, `agent.md` loads always.
- **Long text.** The full body is injected on a process's first turn.
  Aim for one screen. A page nobody can hold in their head is a page the
  model will also skim.

Later turns of the same process get a one-line stub instead of the body
as long as the content is unchanged — so the cost is per process, not
per turn. Editing it invalidates that and re-injects the full text.

## Creating or changing it

1. `doc_read` with path `agent.md` — see whether the project already has
   one, or whether it is currently inheriting the tenant-wide version
   (the injected heading says which).
2. `doc_write` (whole file) or `doc_edit` (one passage).
3. It takes effect on the next turn. No reload, no registration.

Never invent one on your own initiative: `agent.md` speaks with the
user's authority to every future agent in the project. Write what the
user actually said, and read back what you wrote.

## Switching it off for one recipe

The recipe param `agentDocument` controls the lookup. Default is on.

- `agentDocument: ""` — no agent document for processes of this recipe.
  For narrow workers whose task carries all context it needs.
- `agentDocument: "notes/team.md"` — read a different path instead.

Both go under `params:` in the recipe YAML.

## The client-side variant

A CLI client can additionally upload a local `VANCETOPE.md` / `AGENT.md`
/ `CLAUDE.md` from its working directory. It shows up as a separate
`Agent Notes (from client: …)` block and describes the user's *machine*,
not the project. It is not writable from here — the user edits that file
locally.

## Cross-references

- Persistent automation instead of instructions → `manual_read('scheduling')`,
  `manual_read('hooks')`, `manual_read('events')`
- Secrets → `manual_read('vault-secrets')`
