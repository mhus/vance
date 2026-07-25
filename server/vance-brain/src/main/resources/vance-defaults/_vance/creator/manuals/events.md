---
audience: creator
triggers: event_set, event anlegen, event erstellen, event_fire, event testen, event manuell auslösen, webhook testen, UrsaEvent, ursa event, lief der webhook, event log
summary: How to test-fire a configured UrsaEvent without the webhook bearer-token check, and how to read the resulting trigger log document.
---
# How I test events and read their log

An **event** is an HTTP trigger that starts a workflow or a recipe —
typically incoming webhooks (GitHub PR, IoT push, …). The YAMLs live
under `_vance/events/<name>.yaml`. Incoming external requests go through
`POST /brain/{tenant}/event/{project}/{event}` with bearer-token
authentication.

When the user says "test the event", "I want to see if my webhook
works" or "run the github-pr event once", I use the `event_fire` tool —
which works **without** the external bearer token, because I am already
authenticated from within the project scope.

## `event_fire`

```
invoke_tool(
  name = "event_fire",
  params = {
    "name": "github-pr",
    "payload": {                      # optional, passed through under params.payload
      "action": "opened",
      "pull_request": { "number": 42 }
    }
  }
)
```

Answer on success:
```
{ "correlationId": "evt_550e8400-…",
  "targetName": "review-pr",
  "spawnedId": "run_xyz",
  "logPath": "_vance/logs/events/github-pr/2026-06-09T08-15-00Z-evt_550e8400-….md",
  "note": "Event fired. Read '...' via doc_read for the per-trigger log." }
```

Answer on failure: the tool throws an error message with the server
reason (`not_found`, `disabled`, `magrathea_unavailable`,
`spawn_failed`, …). For all failures **except `not_found`** a log
document was still written — `doc_read` on the returned `logPath` shows
the exact details.

## Reading the log document directly

Just like the scheduler, every firing leaves a markdown under
`_vance/logs/events/<eventName>/<isoStamp>-<correlationId>.md`. The front
matter carries `outcome`, `source` (`admin` for `event_fire`, `public`
for a webhook), `httpMethod`, `runAs`, `targetName`, `spawnedId`,
`durationMs`. Auto-TTL: default 7 days, the setting
`events.log.retentionDays` can override this per tenant/project
(tri-state):
- **> 0** → days of retention (≤ 365).
- **0** → kept indefinitely (no TTL).
- **< 0** → logging off, no documents.

```
invoke_tool(
  name = "doc_read",
  params = { "path": "<logPath from event_fire>" }
)
```

## When the user asks: "did the webhook run yesterday?"

`doc_list(pathPrefix = "_vance/logs/events/<eventName>/")` — newest last
(ISO-stamp sorted). One file per run. With `source: public` it is an
externally received webhook, with `source: admin` a test fire (UI or
`event_fire`).

## What I do NOT do

- **Don't create test events** just for trying things out. `event_fire`
  replaces that entirely — don't write a temporary event with a test
  payload and delete it afterward.
- **Don't paste in a bearer token**. If the user shows me a secret, I
  point out that it isn't needed — `event_fire` bypasses the token
  check.
- **No payload dump in the chat** when the payload contains sensitive
  data. The webhook body is NOT persisted in the log document anyway
  (only size + content type), because that would be too risky
  forensically.
