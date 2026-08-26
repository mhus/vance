---
name: rest
summary: vance.rest(...) — calling any Brain route from an app's program, what is closed to apps and why, and declaring what an app needs in its manifest.
triggers: an app needs something the document API cannot give it (the inbox, sessions, templates, follow-up, settings, a mounted API), you are about to say an app cannot reach a feature, or a call came back "closed to apps"
---

# Calling the API from an app

```js
const items = await vance.rest('GET', 'inbox?projectId=' + vance.app.project);
await vance.rest('POST', 'inbox', { title: 'Check this', body: '…' });
```

`vance.rest(method, path, body?)`. The path is **below `/brain/{tenant}/`** —
the tenant is not yours to name. Answers come back parsed; a failure throws with
its status in the message, because a program recovers differently from a 404
than from a 409.

**An app may do what its reader may do.** The host performs the call with the
reader's session, so the permission system decides everything about what may be
seen or written — no extra rights, and no fewer.

## The credential stays outside

The program never holds a token, and could not use one: it runs in a frame with
an opaque origin, so no cookie of ours reaches it. `vance.rest` is the host
making the call and handing back the result.

That is not a limitation to work around — it is why an app written as a document
is allowed to run at all. A token in the program would be stealable and
replayable from anywhere for its whole lifetime; a relayed call is neither.

## What is closed to every app

An app runs in the browser of whoever **opens** it, which is not always its
author. So a click borrowed from the reader must not turn into something that
outlives the visit:

| Closed | Why |
|---|---|
| `access`, `refresh`, `logout`, `oauth` | credentials and session machinery |
| `admin` | grants, catalogues, session exports |
| `share` | sends content out of the house |
| `mcp` | executes any tool, including ones that send mail |
| `compose`, `python`, `script`, `scripts` | runs code on the server |

Everything else is open and answered by the permission system. **This list
cannot be widened from a document** — not by a setting, not by the manifest. If
an app genuinely needs one of these, that is a conversation about the runtime,
not a line to add.

## Declaring what the app needs

Optional, and worth doing:

```yaml
# _app.yaml
custom:
  rest: [documents, inbox, templates]
```

Route **families** — the first path segment — not paths. Then a call outside the
list is refused with the list in the message.

| `rest:` | Meaning |
|---|---|
| absent | unrestricted, below the closed list |
| `[]` | this app asks for no routes at all |
| `[documents, inbox]` | only those |

Absent and `[]` are **different**: one says nothing, the other says "needs
none".

This is not a security boundary on its own — whoever writes `main.js` can write
this line too. Its value is that it is the sentence a reviewer reads before
trusting an app, and it keeps an app's reach as small as it actually is.

## Asking a model

```yaml
# _app.yaml
custom:
  rest: [light-llm]
```

```js
const r = await vance.rest('POST', 'light-llm/' + vance.app.project,
  { recipe: 'app-summary', prompt: text });
// r.text is the reply, verbatim
```

The recipe decides the model, the prompt template and the retry budget — you
supply the input. It has to carry **both** `internal: true` (a config profile,
not a spawnable worker) and **`web: true`** (released for web callers). Without
the second one the call comes back `403` naming the missing line, and that is
the answer to "why can't I call `fook`": nothing is released by default.

A model call therefore needs a recipe document — `_vance/recipes/<name>.yaml` in
the project.

## Reading settings

```yaml
custom:
  rest: [settings]
```

```js
const cfg = await vance.rest('GET',
  'settings/' + vance.app.project + '?prefix=myapp.');
// { "myapp.pageSize": "20", … } — strings, cascade-resolved
```

Either `?prefix=` for a family or `?keys=a,b` for named ones — exactly one of
the two, because "neither" would be a scan of the whole collection. Values come
through the ordinary cascade (project → tenant default), so an app sees the
configuration the server sees for its project.

**A secret is never readable here**, and not because this route checks: the
settings service itself refuses `PASSWORD` and `HIDDEN` values on every generic
read. An encrypted setting is reported exactly like one nobody ever set — that
is deliberate, because answering "that key exists but you may not have it"
confirms a piece of the tenant's configuration.

**Read only.** Writing a setting goes through a **setting form**
(`_vance/setting_forms/<name>.yaml`), which declares which keys it binds and
enforces admin rights per scope. An app that wants to remember something of its
own is usually better off writing a **document** in its own folder — that it can
do already, and it needs nobody's permission.

## Starting and watching a run

An app can start a worker, and then watch it:

```js
const started = await vance.rest('POST', 'processes/' + vance.app.project,
  { recipe: 'app-plan', session: (await vance.app.current()).session,
    goal: 'Plan the inventory app' });
// started.runId is the handle for the run routes below
```

Same release as a model call: the recipe needs **`web: true`**. Which route
applies follows from `internal` — a config profile (`internal: true`) is a
one-shot `light-llm` call, a worker recipe (`internal: false`) is spawnable
here. Asking for the wrong one is refused and says which to use.

A **session** is required, because a think process is owned by one: that is
where its chat log lives and what the reader opens to see what the worker said.
`vance.app.current().session` is the chat beside your app — `null` in a chatless
tab, and then there is nothing to start into.

Watching it:

```js
const runs = await vance.rest('GET', 'runs?projectId=' + vance.app.project);
const one  = await vance.rest('GET', 'runs/' + id + '?projectId=' + vance.app.project);
await vance.rest('POST', 'runs/' + id + '/actions/pause?projectId='
  + vance.app.project);
```

Each run says in `allowedActions` what it currently offers (`PAUSE`, `RESUME`,
`STOP`) — read that rather than guessing, because a run that has moved on
accepts nothing, and an action it does not offer is a silent no-op rather than
an error.

**Read the state back rather than trusting the reply.** A stop on a busy lane is
*staged* — the answer may still say `RUNNING` and be `STOPPED` a second later.
Measured, not assumed. `compose` stays closed: it runs code on the server.

## The tenant may narrow all of this

Above the floor and above your manifest sits a third list: the tenant's policy
for **this app**, decided by an admin in `_vance/config/applications.yaml` (in
the `_tenant` project). Three modes — `forbidden`, `restricted`, `allowed` —
global, per project, or per app path.

You will meet it as a refusal that says so:

```
vance.rest cannot call 'inbox/count': this tenant allows the app only
[documents], which does not include 'inbox'. A tenant admin decides that in
_vance/config/applications.yaml.
```

Three lists in **escalating order of who can change them**: the floor is code,
the policy an admin's, the declaration yours. The refusal says which one spoke —
tell the reader which line and whose it is, rather than that the app is broken.

When an app is refused, the reader may be offered **Request release** — the
tenant's admin gets an inbox item showing exactly what the app declared, and
approving records it. So "forbidden" is a question, not a wall. Two cases where
the button is absent and the message says which: the tenant configured no
recipient, or an admin already named this app in their own file (asking again
would not change it).

`restricted` with no route list means **no REST at all** — and an app can still
be perfectly useful there, because `vance.documents.*` is a different surface and
stays available. A `forbidden` app does not open in the first place: the server
declines to describe it, so there is nothing to mount.

## What it does not do

- **No other host.** `vance.rest('GET', 'https://…')` is refused. The program can
  `fetch` the open internet itself if it must; what it cannot do is send our
  session there.
- **Not a document path.** A REST path is not resolved against the app folder —
  `documents/folder` is a route, not a file. For documents use
  `vance.documents.*`, which *is* app-relative and remembers versions
  (`manual_read('bistromath/data')`).
- **No LLM shortcut yet.** There is no `vance.llm`; a model call needs a recipe
  to bound its cost. Ask before promising one.
