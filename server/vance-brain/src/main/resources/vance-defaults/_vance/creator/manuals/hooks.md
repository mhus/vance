---
audience: creator
triggers: hook, hook_set, hook_list, hook_get, hook_delete, hook anlegen, hook erstellen, reagiere auf, wenn ein prozess fertig, on completion, on failure, inbox trigger, process.completed, process.failed, inbox.item.created, UrsaHook, ursa hook, event-driven
summary: How to create and maintain hooks — event-driven triggers that fire a recipe, script or workflow when a brain event (process.completed / process.failed / inbox.item.created) is published. YAML shape, the TriggerAction disjunction, the catalog of subscribable events, and the anti-patterns for not over-automating.
---
# Wie ich Hooks anlege und pflege

Ein **Hook** ist ein event-getriebener Trigger: sobald im Brain ein
bestimmtes Ereignis passiert (ein Prozess wird fertig, ein Prozess
scheitert, ein Inbox-Item entsteht), spawnt der Hook einen Worker —
über ein Recipe, ein Skript oder einen Workflow. Anders als ein
**Scheduler** (zeitgesteuert) und anders als ein **Event** (eingehender
Webhook) reagiert ein Hook auf *interne* Brain-Ereignisse.

Hooks liegen als YAML-Dokumente unter `_vance/hooks/<event>/<name>.yaml`
im Projekt (oder tenant-weit unter `_vance/hooks/…`). Sie werden aktiv,
sobald ich sie geschrieben habe — der Brain registriert sie nach jedem
Schreiben selbständig (Delta-Refresh).

## Welches Tool wofür

| Aufgabe | Tool |
|---|---|
| Vorhandene Hooks im Projekt sehen | `hook_list` |
| Vollständiges YAML eines Hooks nachschlagen | `hook_get` |
| Hook anlegen oder ändern | `hook_set` |
| Entfernen | `hook_delete` |
| Registry neu einlesen (selten nötig) | `hook_refresh` |

`hook_set` ist idempotent: existiert bereits ein Hook mit demselben
`(event, name)`, wird sein YAML überschrieben (der vorherige Stand wird
vom Document-Layer automatisch archiviert). Die Antwort trägt
`created: true|false`, damit ich weiß, welcher Pfad lief.

## Auf welche Events ein Hook hören kann

`hook_set` braucht den **Wire-Namen** des Events als eigenen Parameter
(`event`), getrennt vom YAML-Body. Live in v1:

- **`process.completed`** — ein Think-Process ist erfolgreich terminiert.
- **`process.failed`** — ein Think-Process ist gescheitert.
- **`inbox.item.created`** — ein neues Inbox-Item wurde erzeugt.

Reserviert (gültige Hook-Dokumente, feuern in v1 aber **nicht**, weil
der Emitter noch fehlt): `session.suspended`, `session.resumed`,
`insight.saved`, `relation.created`. Ich lege dafür keine Hooks „auf
Vorrat" an — wenn der User einen davon will, weise ich darauf hin, dass
er derzeit nicht ausgelöst wird.

## Hook anlegen (`hook_set`)

Der YAML-Body definiert **genau eine** Aktion — `recipe:`, `script:`
oder `workflow:` (Disjunktion, wird beim Parsen erzwungen). Beispiel:
nach jedem gescheiterten Prozess ein kurzes Review-Recipe starten.

```
invoke_tool(
  name = "hook_set",
  params = {
    "event": "process.failed",
    "name": "post-failure-review",
    "yaml": """
description: \"Startet nach jedem Fehlschlag eine kurze Ursachen-Analyse.\"
recipe: \"analyze\"
initialMessage: |
  Ein Prozess ist gescheitert. Fasse die wahrscheinliche Ursache
  in drei Sätzen zusammen und schlage einen nächsten Schritt vor.
enabled: true
"""
  }
)
```

### Body-Felder

- **Aktion (Pflicht, genau eins):**
  - `recipe: "<name>"` — spawnt einen Worker mit diesem Recipe.
    Optional dazu `initialMessage:` (der erste Prompt) und `params:`.
  - `script: { source: <document|inline>, path: "<pfad>" }` — führt ein
    Skript-Dokument aus (optional `dirName`, `timeoutSeconds`, `params`).
  - `workflow: <name>` — startet einen benannten Workflow.
- **`enabled`** (optional, Default `true`) — auf `false` setzen, um den
  Hook zu deaktivieren, ohne ihn zu löschen.
- **`description`** (optional) — was der Hook tut, für Liste/Log.
- **`timeout`** (optional) — Zahl in Sekunden oder Duration-String
  (`"30s"`, `"5m"`). Es gibt eine harte Obergrenze pro Hook.
- **`runAs`** (optional) — in wessen Namen der Worker läuft (dessen
  Inbox bekommt Nachrichten/Errors). Ohne Angabe der `createdBy` des
  Dokuments.
- **`tags`** (optional) — freie Labels für Filterung.
- **`params`** (optional) — Parameter-Map, die an die Aktion durchgereicht
  wird.

**Schema-Hinweis:** Das alte `type: js|llm`-Format wird nicht mehr
akzeptiert. JS-Hooks werden zu `script: { source: document, path: … }`,
LLM-Hooks zu einem Skript, das `vance.lightllm.call(...)` ruft.

## Bestehende Hooks anpassen

`hook_set` braucht das **komplette** YAML — Full-Replace, keine
Patch-Operation. Der vorherige Stand wird automatisch archiviert.
Workflow:

1. `hook_get(event, name)` → aktuelles YAML
2. Stelle anpassen
3. `hook_set(event, name, yaml)` mit dem geänderten Gesamttext

Nur deaktivieren: `enabled: false` ins YAML, `hook_set`.

## Was ich NICHT anlege

- **Keine Hooks auf Vorrat.** Ein Hook feuert bei *jedem* Auftreten
  seines Events — das kann schnell laut werden. Erst fragen, ob das
  wirklich bei jedem `process.completed` laufen soll.
- **Keine Hooks für reservierte Events** (`session.*`, `insight.saved`,
  `relation.created`) ohne den Hinweis, dass sie in v1 nicht feuern.
- **Keine Selbst-Trigger-Schleifen.** Ein Hook auf `process.completed`,
  der selbst einen Prozess spawnt, der wieder `process.completed`
  feuert, läuft in eine Endlosschleife. Vor dem Anlegen prüfen, ob die
  Hook-Aktion dasselbe Event erneut auslöst.
- **Keine doppelten Hooks** für dasselbe Thema. Erst `hook_list`, dann
  updaten oder fragen.
