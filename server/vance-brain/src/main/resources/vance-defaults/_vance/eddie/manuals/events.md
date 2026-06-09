---
audience: eddie
triggers: event_fire, event testen, event manuell auslösen, webhook testen, UrsaEvent, ursa event, lief der webhook, event log
summary: How Eddie test-fires a configured UrsaEvent without the webhook bearer-token check, and how to read the resulting trigger log document.
---
# Wie ich Events teste und ihr Log lese

Ein **Event** ist ein HTTP-Trigger, der einen Workflow oder ein Recipe
startet — typisch eingehende Webhooks (GitHub PR, IoT-Push, …). Die
YAMLs liegen unter `_vance/events/<name>.yaml`. Eingehende externe
Requests gehen über `POST /brain/{tenant}/event/{project}/{event}` mit
Bearer-Token-Authentifizierung.

Wenn der User sagt "teste mal das Event", "ich will sehen ob mein
Webhook funktioniert" oder "lass das github-pr Event einmal laufen",
nutze ich das `event_fire`-Tool — das geht **ohne** den externen
Bearer-Token, weil ich vom Project-Scope aus schon authentifiziert bin.

## `event_fire`

```
invoke_tool(
  name = "event_fire",
  params = {
    "name": "github-pr",
    "payload": {                      # optional, wird unter params.payload weitergereicht
      "action": "opened",
      "pull_request": { "number": 42 }
    }
  }
)
```

Antwort bei Erfolg:
```
{ "correlationId": "evt_550e8400-…",
  "targetName": "review-pr",
  "spawnedId": "run_xyz",
  "logPath": "_vance/logs/events/github-pr/2026-06-09T08-15-00Z-evt_550e8400-….md",
  "note": "Event fired. Read '...' via document_read for the per-trigger log." }
```

Antwort bei Misserfolg: das Tool wirft eine Fehler-Message mit dem
Server-Reason (`not_found`, `disabled`, `magrathea_unavailable`,
`spawn_failed`, …). Bei allen Misserfolgen **außer `not_found`** wurde
trotzdem ein Log-Document geschrieben — `document_read` auf den
zurückgegebenen `logPath` zeigt die genauen Details.

## Log-Document direkt lesen

Genau wie beim Scheduler hinterlässt jedes Auslösen ein Markdown unter
`_vance/logs/events/<eventName>/<isoStamp>-<correlationId>.md`. Front-
Matter trägt `outcome`, `source` (`admin` für `event_fire`, `public`
für Webhook), `httpMethod`, `runAs`, `targetName`, `spawnedId`,
`durationMs`. Auto-TTL: Default 7 Tage, Setting `events.log.retentionDays`
kann das pro Tenant/Projekt überschreiben (tri-state):
- **> 0** → Tage Retention (≤ 365).
- **0** → unbegrenzt erhalten (kein TTL).
- **< 0** → Logging aus, keine Documents.

```
invoke_tool(
  name = "document_read",
  params = { "path": "<logPath aus event_fire>" }
)
```

## Wenn der User fragt: „lief das Webhook gestern?"

`document_list(pathPrefix = "_vance/logs/events/<eventName>/")` —
neueste zuletzt (ISO-Stamp sortiert). Pro Lauf eine Datei. Bei
`source: public` ist es ein extern empfangener Webhook, bei
`source: admin` ein Test-Fire (UI oder `event_fire`).

## Was ich NICHT mache

- **Keine Test-Events anlegen** nur fürs Probieren. `event_fire`
  ersetzt das vollständig — kein temporäres Event mit Test-Payload
  schreiben und hinterher löschen.
- **Kein Bearer-Token reinkopieren**. Wenn der User mir ein Secret
  zeigt, weise ich darauf hin, dass das nicht nötig ist — `event_fire`
  bypasst den Token-Check.
- **Kein Payload-Dump im Chat**, wenn die Payload sensitive Daten
  enthält. Der Webhook-Body wird sowieso NICHT im Log-Document
  persistiert (nur Größe + Content-Type), weil das forensisch zu
  riskant wäre.
