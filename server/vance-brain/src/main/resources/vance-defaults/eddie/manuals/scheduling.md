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
| Neuen Scheduler anlegen | `scheduler_create` |
| Bestehenden ändern | `scheduler_update` |
| Entfernen | `scheduler_delete` |

Vor jedem create/update sollte ich kurz `scheduler_list` aufrufen — wenn der
User „einen Reminder für morgen früh" will und sowas schon existiert, lege
ich keinen zweiten an, sondern frage nach.

## Wiederkehrender Scheduler (`cron:`)

Standardfall: jeden Wochentag um 8:00 Uhr Berliner Zeit:

```
invoke_tool(
  name = "scheduler_create",
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
  name = "scheduler_create",
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

`scheduler_update` braucht das **komplette** YAML — keine Patch-Operation,
sondern Full-Replace. Workflow:

1. `scheduler_get(name)` → aktuelles YAML
2. Stelle anpassen (z. B. nur die Cron-Zeile)
3. `scheduler_update(name, yaml)` mit dem geänderten Gesamttext

Wenn ich nur deaktivieren will: `enabled: false` ins YAML, `scheduler_update`.
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

Wenn `scheduler_create`/`update`/`delete` mit „is locked" antwortet, dem
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

## Wenn der User fragt: „lief das gestern?"

`scheduler_list` zeigt pro Eintrag `lastRun` — wenn da was steht (Typ
`STARTED`/`COMPLETED`/`FAILED`/`SKIPPED`), kann ich es dem User berichten.
Für mehr Details (welcher Process, was ist passiert) ist das Web-UI der
bessere Pfad — ich kann den User darauf verweisen.

## Was ich NICHT anlege

- Scheduler für Themen, die der User nur nebenbei erwähnt hat. Erst
  fragen, ob das wirklich automatisiert laufen soll.
- Scheduler mit `cron: \"* * * * * *\"` (jede Sekunde) oder ähnlich
  aggressiv — das ist fast immer ein Missverständnis. Nachfragen.
- Doppelte Scheduler für dasselbe Thema. Erst listen, dann updaten oder
  fragen.
