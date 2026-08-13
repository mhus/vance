# Workflow

Ein **Workflow** ist eine Automation, die du einmal aufschreibst und
beliebig oft startest: eine State-Machine aus typisierten Tasks, die
Session-Grenzen überlebt — manche Workflows laufen Wochen und warten
auf einen Menschen oder einen Timer. Er ist die Schicht *über* den
Agenten: ein Workflow startet sie, verzweigt anhand dessen, was sie
zurückgeben, und macht danach weiter.

Das Dokument, das du offen hast, *ist* der Plan. Es gibt keine
separate Definition woanders.

## Kind und Ort sind zwei verschiedene Dinge

- `kind: vance-workflow` im `$meta`-Header sagt, **was dieses Dokument
  ist**. Das gilt überall im Projekt — als Entwurf, als Kopie, als
  Variante zum Ausprobieren. Alle werden gleich validiert.
- **Startbar** ist nur ein Dokument unter
  `_vance/workflows/<name>.yaml`. Dort sucht der Loader Workflows per
  Name; der Dateiname ohne `.yaml` ist der Name des Workflows.

Also: schreib ihn wo du willst, und schieb ihn nach
`_vance/workflows/`, wenn er scharf werden soll.

## Die zwei Reiter

- **Ansicht** — die State-Machine als Diagramm. Read-only und
  abgeleitet: es gibt kein gespeichertes Layout, jedes Rendern legt
  den Graphen neu.
- **Bearbeiten** — das rohe YAML. Hier änderst du.

### Das Diagramm lesen

Jeder Kasten ist ein State. Der Farbstreifen links zeigt den
Task-Typ; grün oder rot markiert den Ausgang eines Terminal-States.
Der umrandete Kasten ist der Start-State, `↻` markiert einen State
mit `retry:`-Block. Unter dem Namen steht das eine, was den State
identifiziert — das Recipe, das Tool, das Kommando, die Wartezeit.

Pfeile sind nach ihrer Herkunft unterschieden:

- **durchgezogen** — ein `on:`-Outcome. Der normale Weg.
- **gestrichelt, warm** — ein `catch:`-Fehlerfall. Die Fehlerspur.
- **farbig** — ein `condition_task`-Zweig; gestrichelt für `else:`.

Ein Pfeil auf einen State, den es nicht gibt, wird rot auf einen
gestrichelten Geister-Kasten gezeichnet und im Banner über dem
Diagramm aufgeführt. Das Banner zeigt die Probleme, die das Bild
sehen kann — eine ins Leere zeigende Transition, ein unbekannter
Task-Typ, ein `start:` ohne Ziel. Die verbindliche Prüfung läuft auf
dem Server und erscheint zusätzlich in den Validierungs-Findings des
Dokuments.

Mit dem Button rechts oben schaltest du zwischen vertikalem und
horizontalem Layout um; breite Graphen lesen sich quer besser.

## Der kleinste Workflow, der läuft

```yaml
$meta:
  kind: vance-workflow

start: work

states:
  work:
    type: agent_task
    recipe: jeltz
    params:
      prompt: "Was der Agent tun soll."
    storeAs: work_result
    on:
      success: done
    catch:
      agent_error: failed

  done:
    type: terminal
    outcome: success

  failed:
    type: terminal
    outcome: failure
```

`start:` und `states:` sind die einzigen Pflichtfelder. Jedes Ziel
eines `on:`-, `catch:`- oder `transitions:`-Eintrags muss einen
deklarierten State benennen — sonst weist der Parser die Datei ab.

## Task-Typen

| `type:` | Was er tut |
|---|---|
| `agent_task` | Startet einen Agenten per Recipe und wartet auf ihn |
| `tool_task` | Ruft genau ein Tool auf |
| `shell_task` | Führt ein Shell-Kommando in einem Workspace aus |
| `script_task` | Führt ein JS-Skript aus Dokument oder Workspace aus |
| `gate_task` | Fragt einen Menschen per Inbox-Item und wartet |
| `timer_task` | Wartet eine Dauer ab (`7d`, `4h`, `30m`) |
| `condition_task` | Verzweigt über einen Ausdruck, ohne Seiteneffekt |
| `workflow_task` | Startet einen anderen Workflow und wartet auf ihn |
| `terminal` | Beendet den Lauf mit `success` oder `failure` |

## Daten weiterreichen

Ein State schreibt sein Ergebnis mit `storeAs: <key>` in eine
Variable. Ein späterer liest sie als `${state.<key>}` — im Prompt, in
einem Tool-Parameter, im Gate-Titel, überall wo ein String steht.
Aufruf-Argumente aus `parameters:` liest du als `${params.<key>}`.
Ein fehlender Key wird zum leeren String, nicht zum Fehler.

## Starten

Über den Reiter *Workflows* unter Insights (Workflow wählen,
Parameter ausfüllen, starten), über einen Scheduler-Eintrag, über den
REST-Endpunkt, oder durch einen Agenten mit dem `workflow_start`-Tool.

Jeder Start friert das komplette YAML in den Lauf ein. Änderungen an
diesem Dokument berühren einen laufenden Lauf **nie** — du fixt einen
Fehler, der nächste Start nimmt ihn mit, und die alten Läufe laufen
auf der Definition zu Ende, mit der sie begonnen haben.
