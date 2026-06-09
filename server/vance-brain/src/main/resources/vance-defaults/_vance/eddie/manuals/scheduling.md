---
audience: eddie
triggers: scheduler, scheduler_set, scheduler_list, scheduler_get, scheduler_delete, scheduler_fire, scheduler manuell auslösen, scheduler testen, scheduler log, lief der scheduler, cron, at:, erinnere mich, reminder, jeden Montag, morgen früh, recurring task, einmalig, daily briefing, locked scheduler, lockMode, runAs, timezone, Quartz cron, IANA
summary: How Eddie creates and maintains schedulers, fires them manually for testing, and reads the per-run log documents — Quartz 6-field cron vs. one-shot `at:`, IANA timezones, recipe choice, lockMode, runAs semantics, plus `scheduler_fire` + `_vance/logs/scheduler/<name>/…` for end-to-end verification.
---
# Wie ich Scheduler anlege und pflege

Ein **Scheduler** ist ein zeitgesteuerter Trigger, der einen Worker-Process
spawnt — wiederkehrend per Cron oder einmalig zu einem konkreten Zeitpunkt.
Wenn der User sagt „erinnere mich jeden Montag um 9 Uhr an die Sprint-Reviews"
oder „starte das Morgenprogramm morgen um 8", lege ich genau dafür einen
Scheduler an.

Schedulers liegen als YAML-Dokumente unter `_vance/scheduler/<name>.yaml` im
Projekt. Sie laufen, sobald ich sie geschrieben habe — der Brain registriert
sie selbständig nach jedem Schreiben (Delta-Refresh).

## Welches Tool wofür

| Aufgabe | Tool |
|---|---|
| Vorhandene Scheduler im Projekt sehen | `scheduler_list` |
| Vollständiges YAML eines Scheduler nachschlagen | `scheduler_get` |
| Scheduler anlegen oder ändern | `scheduler_set` |
| Entfernen | `scheduler_delete` |
| Sofort testweise auslösen (am Cron vorbei) | `scheduler_fire` |
| Lauf-Ergebnis nachlesen (Outcome, Timeline) | `document_read` auf `_vance/logs/scheduler/<name>/…` |

Vor jedem `scheduler_set` sollte ich kurz `scheduler_list` aufrufen — wenn der
User „einen Reminder für morgen früh" will und sowas schon existiert, lege
ich keinen zweiten an, sondern aktualisiere den bestehenden oder frage nach.

## Wiederkehrender Scheduler (`cron:`)

Standardfall: jeden Wochentag um 8:00 Uhr Berliner Zeit:

```
invoke_tool(
  name = "scheduler_set",
  params = {
    "name": "morning-briefing",
    "yaml": """
description: \"Tägliches Morgen-Briefing.\"
cron: \"0 0 8 * * MON-FRI\"
timezone: \"Europe/Berlin\"
recipe: \"default\"
initialMessage: |
  Erstelle das tägliche Briefing für heute.
"""
  }
)
```

Wichtig:
- **Cron-Format ist 6-Feld-Quartz**, NICHT 5-Feld-Unix:
  `<sek> <min> <std> <tag> <monat> <wochentag>`. Sekunden sind Pflicht.
- **Timezone** ist eine IANA-Zone (`Europe/Berlin`, `America/New_York`, …),
  nicht ein Offset. Fehlt sie, gilt UTC — bei „lokalen" Zeiten vom User
  immer explizit setzen.
- **`recipe`** ist Pflicht — der spawnende Worker braucht eine Engine.
  Default-Wahl: `default` (ford-basiert, fast/billig). Wenn der User
  explizit recherchieren will: `analyze`. Für mehrstufige Arbeit: `marvin`.

## Einmaliger Scheduler (`at:`)

Für „mach das genau einmal um Zeitpunkt X" — kein cron, sondern `at:`:

```
invoke_tool(
  name = "scheduler_set",
  params = {
    "name": "review-deck-tomorrow",
    "yaml": """
description: \"Review-Deck-Vorbereitung vor dem Termin.\"
at: \"2026-05-14T07:30:00\"
timezone: \"Europe/Berlin\"
recipe: \"default\"
initialMessage: |
  Gehe die Slides im Workspace durch und liste die offenen Punkte auf.
"""
  }
)
```

Datumsformat-Regeln:
- **`2026-05-14T07:30:00`** — lokale Zeit, wird gegen `timezone:` aufgelöst.
  Wenn der User sagt „morgen um 8" und in Berlin ist, das ist das richtige
  Format.
- **`2026-05-14T07:30:00+02:00`** — mit Offset, wenn der User explizit
  Offset nennt.
- **`2026-05-14T05:30:00Z`** — UTC, wenn ich neutralisiert speichern will.

Was nach dem Feuern passiert:
- Der Process wird gespawnt, das YAML wird **automatisch in den Projekt-
  Mülleimer** (`_bin/`) verschoben. Es taucht in `scheduler_list` nicht mehr
  auf.
- Die Lauf-Historie bleibt im Event-Log und ist über das Web-UI sichtbar.
- Restorable über den Document-Layer falls der User „mach das nochmal"
  meint — aber meist legt man dann lieber einen frischen Scheduler an.

Wenn der User „erinnere mich" sagt, ohne genau zu sagen, ob einmalig oder
wiederkehrend: **nachfragen**. „Soll das nur einmal am Donnerstag laufen
oder jeden Donnerstag?" — die zwei YAMLs sind nicht austauschbar.

## „Heute Abend" / „in 2 Stunden" — Zeitumrechnung

`current_time` gibt mir die aktuelle Server-Zeit. Wenn der User relative
Angaben macht („in 2 Stunden", „heute Abend 19 Uhr"), rechne ich das
**vorher** in absolute ISO-8601-Form um und schreibe die fertige Zeit ins
`at:`-Feld. Niemals `at: \"in 2 Stunden\"` schreiben — die Server-Seite
parst nur ISO-Datums.

## Bestehende Scheduler anpassen

`scheduler_set` braucht das **komplette** YAML — keine Patch-Operation,
sondern Full-Replace. Der vorherige Stand wird vom Document-Layer
automatisch archiviert; ich verliere also nichts beim Überschreiben.
Workflow:

1. `scheduler_get(name)` → aktuelles YAML
2. Stelle anpassen (z. B. nur die Cron-Zeile)
3. `scheduler_set(name, yaml)` mit dem geänderten Gesamttext

Wenn ich nur deaktivieren will: `enabled: false` ins YAML, `scheduler_set`.
Re-aktivieren ist dasselbe Spiel rückwärts.

## Gesperrte Scheduler (`lockMode`)

Manche Scheduler darf ich nicht anfassen — typisch Admin-Eintragungen
(Backups, Compliance-Checks). Im `scheduler_list`-Output haben sie
`"locked": true` und ein `lockMode`-Feld:

- `lockMode: "protected"` → ich sehe ihn, kann ihn aber nicht ändern oder
  löschen. Wenn der User Änderungen wünscht, muss er das im Web-UI machen.
- `lockMode: "hidden"` → tauchen gar nicht erst in der Liste auf. Wenn
  ich versehentlich einen Namen errate, der gesperrt ist, lehnt der
  Server die Mutation ab.

Wenn `scheduler_set`/`scheduler_delete` mit „is locked" antwortet, dem
User klar sagen: „dieser Scheduler ist admin-geschützt und kann nicht über
mich geändert werden." Nicht versuchen, das mit einem leicht anderen Namen
zu umgehen — das ist Bypass-Versuch.

## Welcher User läuft den Scheduler?

`runAs:` legt fest, wessen Inbox die Nachrichten/Errors des Schedulers
bekommt. Ohne explizites `runAs:` ist es der `createdBy` des Doc — also
der User, in dessen Namen ich gerade arbeite. Das ist meistens richtig.

Falls der User explizit jemand anderen meint („leg den Scheduler für
road-runner an"), setze ich `runAs: \"road-runner\"`. Voraussetzung: das ist
ein gültiger User im aktuellen Tenant — sonst landen Inbox-Items im Leeren.

## Scheduler testweise auslösen (`scheduler_fire`)

Bevor ich einen neuen Scheduler dem Cron-Lauf überlasse, sollte ich ihn
einmal probefeuern, damit klar ist: greift das Recipe? Bekommt der
RunAs-User die Antwort? Genau dafür ist `scheduler_fire`:

```
invoke_tool(
  name = "scheduler_fire",
  params = { "name": "morning-briefing" }
)
```

Antwort:
```
{ "correlationId": "run_550e8400-…",
  "logPath": "_vance/logs/scheduler/morning-briefing/2026-06-09T08-15-00Z-run_550e8400-….md",
  "note": "Run started. Read '...' via document_read for status/outcome." }
```

- Der Lauf geht durch denselben Code-Pfad wie ein Cron-Tick — Overlap-
  Policy gilt, Event-Log, Metriken, alles. Unterschied: das Lauf-Document
  trägt `trigger: manual` statt `trigger: cron`.
- Der `logPath` ist **sofort** lesbar; bis der Process terminiert steht
  dort `outcome: pending`. Nach ein paar Sekunden nochmal lesen.
- Bei laufendem Cron-Tick lehnt der Server mit „skipped overlap" ab —
  Overlap-Policy entscheidet wie immer.

Wenn der User sagt „teste das mal", „lass den scheduler einmal laufen"
oder „ich will sehen ob das klappt": **fire-Tool benutzen**, nicht einen
zweiten temporären Scheduler mit `at:` anlegen.

## Wenn der User fragt: „lief das gestern?" / „warum hat das nicht funktioniert?"

Jeder Lauf — Cron-getriggert oder via `scheduler_fire` — hinterlässt ein
Markdown-Document unter:

```
_vance/logs/scheduler/<schedulerName>/<isoStamp>-<correlationId>.md
```

mit YAML-Front-Matter (`outcome`, `trigger`, `firedAt`, `completedAt`,
`durationMs`, `processId`, …) und einer Timeline-Sektion im Body. Die
Documents werden via MongoDB-TTL automatisch gelöscht — Default 7 Tage,
pro Tenant oder Projekt über die Setting `scheduler.log.retentionDays`
einstellbar (tri-state):
- **> 0** → Retention in Tagen (≤ 365).
- **0** → keine Expiry, Documents bleiben unbegrenzt erhalten (z.B. für Compliance).
- **< 0** → Logging komplett aus, keine Documents werden geschrieben.

So gehe ich vor:

1. `document_list(pathPrefix = "_vance/logs/scheduler/<schedulerName>/")`
   — listet alle Läufe des Schedulers, neueste zuletzt (Pfad ist
   ISO-sortierbar).
2. `document_read(path = "<einer der gelisteten Pfade>")` — Front-Matter
   und Timeline analysieren.
3. Bei `outcome: failed` → die `## Error`-Sektion im Body zeigt die
   Fehler-Message. Bei `outcome: skipped_overlap` → ein anderer Lauf war
   noch aktiv. Bei `outcome: pending` → der Lauf läuft noch, oder der
   Brain ist nach STARTED gecrasht.

Für eine schnelle Übersicht („wann lief das zuletzt") reicht weiterhin
`scheduler_list` mit seinem `lastRun`-Feld — die Detail-Forensik
passiert über die Log-Documents.

## Was ich NICHT anlege

- Scheduler für Themen, die der User nur nebenbei erwähnt hat. Erst
  fragen, ob das wirklich automatisiert laufen soll.
- Scheduler mit `cron: \"* * * * * *\"` (jede Sekunde) oder ähnlich
  aggressiv — das ist fast immer ein Missverständnis. Nachfragen.
- Doppelte Scheduler für dasselbe Thema. Erst listen, dann updaten oder
  fragen.
