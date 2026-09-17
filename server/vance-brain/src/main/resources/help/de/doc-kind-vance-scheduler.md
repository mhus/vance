# Scheduler

Ein **Scheduler** ist ein zeitgesteuerter Auslöser: Er feuert ein Recipe
(oder einen Workflow, oder ein Skript) zu einem Zeitpunkt, den du
wählst — jede Stunde, jeden Werktag um 9, am 15. jedes Monats oder
genau einmal morgen früh. Das Dokument, das du offen hast, *ist* die
Definition; es gibt keine zweite Kopie.

## Kind und Ort sind zwei verschiedene Dinge

- `kind: vance-scheduler` im `$meta`-Header sagt, **was dieses Dokument
  ist**. Das gilt überall im Projekt — als Entwurf, als Kopie, als
  Variante zum Ausprobieren. Alle werden gleich validiert.
- **Aktiv** ist nur ein Dokument unter `_vance/scheduler/<name>.yaml`:
  bei der Uhr des Brain registriert und feuernd. Der Dateiname ohne
  `.yaml` ist der Name des Schedulers. Alles, was einen Scheduler über
  seinen Namen anspricht — die `scheduler_set`-Tools des Agenten, die
  REST-API, der Tab Insights → Scheduler — findet ihn nur dort.

Woanders liegt er als Entwurf. Das Formular bearbeitet ihn trotzdem; der
Hinweis oben erinnert daran, dass er nicht feuert.

## Das Formular

Die Ansicht bearbeitet die Definition ohne rohes YAML:

- **Was passieren soll** — Beschreibung, Trigger-Ziel (Recipe, Workflow
  oder Skript) und der Prompt, den jeder Lauf erhält.
- **Wann er feuert** — wähle eine Form:
  - **Stündlich** — alle N Stunden, zu einer festen Minute.
  - **Täglich** — eine Uhrzeit.
  - **Wöchentlich** — Wochentage plus Uhrzeit.
  - **Monatlich** — Tag des Monats plus Uhrzeit.
  - **Einmalig** — ein konkreter Zeitpunkt; nach dem Feuern wandert das
    Dokument in den Papierkorb.
  - **Andere (Cron-Ausdruck)** — alles andere. Ein Cron, den das
    Formular nicht ausdrücken kann, landet automatisch hier: der rohe
    Ausdruck steht exakt wie gespeichert und ist als Text editierbar.
    Das Formular schreibt nie still einen Zeitplan um, den es nicht
    versteht.
- **Identität** — `runAs`, der User, dessen Inbox Fragen und Fehler der
  Läufe erhält.

Die graue Zeile unter dem Zeitplan ist live: Sie zeigt den
Cron-Ausdruck (bzw. den `at:`-Wert), den der aktuelle Formularstand
speichern wird. Die Zeitzone ist eine IANA-Zone und muss gesetzt sein,
damit die Uhrzeiten das bedeuten, was du erwartest — ohne sie rechnet
das Brain mit UTC.

Alles, was das Formular nicht zeigt (Recipe-`params`, `tags`,
`lockMode`, der `$meta`-Header), bleibt unangetastet erhalten — das
Formular bearbeitet nur die Felder, die ihm gehören. Für den Rest gibt
es den Raw-YAML-Edit-Modus.

## Manuelle Läufe und Historie

Der Tab Insights → Scheduler (und das `scheduler_fire`-Tool des
Agenten) löst einen Scheduler sofort aus, am Cron vorbei. Jeder Lauf
hinterlässt ein Log-Dokument unter `_vance/logs/scheduler/<name>/` und
eine Zeile im Aktivitäts-Feed — das Lauf-Historie-Panel dort zeigt,
was wann passiert ist und warum ein Lauf scheiterte.
