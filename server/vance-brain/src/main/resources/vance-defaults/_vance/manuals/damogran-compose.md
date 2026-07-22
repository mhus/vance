---
triggers: compose, damogran, workspace, workspace ausführen, prepare files, exec im workspace, kompilieren, latex, tex nach pdf, python analyse, dateien importieren, git clone, git pull, git push, git commit, repo klonen, batch
summary: Provision a named workspace, import documents, run a linear list of tasks (exec/js/python/llm/tex), export results — via compose_run. Run/clear a compose block held by a document via compose_block_run / compose_block_clear_output.
requires-tools: compose_run, compose_block_run, compose_block_clear_output
---

# Damogran — Workspace Compose

Run a *compose*: a named workspace is provisioned, documents/URLs are imported
into it, a linear list of tasks runs against it, and results are exported back
to documents. Use it when a job needs a real working directory + files + shell/
script/LLM steps — not for a single tool call.

## Wann verwenden

- Dateien aus Dokumenten in einen Arbeits-Workspace holen, etwas darauf laufen
  lassen (Shell/Skript/Analyse), Ergebnis zurückschreiben.
- LaTeX → PDF kompilieren (`tex-task`).
- Mehrere Schritte, die aufeinander Dateien im selben Verzeichnis brauchen.

Nicht für: verzweigte/wiederholende Workflows (das ist ein Workflow), oder einen
einzelnen Tool-Call.

## Tool

```
compose_run(composePath="documents/build.compose.yaml")
compose_run(composeYaml="workspace:\n  name: …\ntasks:\n  - …")
```

Genau eines von `composePath` (ein `compose`-Dokument) oder `composeYaml`
(inline). Der Lauf ist **linear** und **hält beim ersten fehlgeschlagenen Task**.

**Async:** kurze Composes antworten inline mit `{ success, workspace, tasks:
[{status, outputs, error?}] }`. Ein **langer** Lauf (>15s) antwortet mit
`{ runId, running: true }` — dann **beende deinen Turn und warte**; du bekommst
ein `COMPOSE_FINISHED`-ProcessEvent (Payload: `runId`, `status`, `result`),
sobald er fertig ist. Nicht pollen/blockieren. Für lange Läufe (z.B. Training)
im Task `deadlineSeconds: 0` setzen (kein Hard-Kill, läuft bis zum Ende).

### Einen Compose-Block ausführen, an dem der Nutzer arbeitet

Wenn der Nutzer ein `compose`-Dokument offen hat und du es **erledigen** (nicht
nur editieren) sollst — ausführen und das Ergebnis sichtbar in den Block
schreiben:

```
compose_block_run(id="<documentId>")        # oder path="…"
compose_block_clear_output(id="<documentId>")
```

`compose_block_run` liest das Manifest aus dem **gespeicherten** Dokument (deine
`doc_edit`s aus diesem Turn sind also drin — kein Race), führt es aus und
schreibt die Artefakte in den managed `$output:`-Block zurück; ein offener Editor
zeigt sie **live** (genau wie der Run-Button). Gleiche Async-Semantik wie
`compose_run` (inline oder `COMPOSE_FINISHED`). `compose_block_clear_output`
entfernt `$output:`/`$run:` wieder (Manifest bleibt). Nutze diese beiden, wenn
das Ziel ein Dokument-Block ist; `compose_run` dagegen für ad-hoc/`composePath`.

## Manifest

```yaml
title: Report bauen        # optional; im View über dem Run-Button gezeigt
description: Sortiert + fasst zusammen   # optional
showSource: false          # optional, nur UI (Runner ignoriert): true = YAML
                           #   auch in der gerenderten Workbook-Seite zeigen
                           #   (Default: nur Titel/Beschreibung + Run + Outputs)
autoRun: true              # optional, nur UI: false = beim „Run All Until"
                           #   überspringen (per ▶ manuell weiter ausführbar)
session:                   # optional: Session-Prozess bereitstellen (für `agent`,
  enabled: true            #   `spawn` oder tool-nutzendes `js` beim CHATLOSEN Button-Run).
  name: my-agent           #   optional: stabile Identität → Re-Run setzt fort (Kontinuität)
  recipe: arthur           #   optional: macht den Prozess zum Agenten (für `agent`-Task)
  clean: false             #   true = Prozess vor dem Run zurücksetzen (frischer Start)
workspace:
  name: my-work            # benannter Workspace (überlebt Re-Runs in der Session)
  type: temp               # temp | git | node | python
  clear: false             # true = vorher leeren, dann leer neu anlegen
  target: WORK             # WORK (default) | CLIENT (exec-only, Foot) | DAEMON (exec-only)
  options: { repoUrl: … }  # git: repoUrl/branch — node/python: packages: [numpy, pandas==2.0]
import:
  - from: vance:data.csv   # vance:<path> = Dokument; http(s):// = externe Quelle
    to: data.csv           # workspace-relativ (import-Ziel ist IMMER lokal)
  - from: git:https://github.com/acme/repo.git   # clone (pull bei Re-Run)
    to: repo               # Ordner im Workspace
    branch: main           # optional; credentialAlias optional
tasks:
  - type: exec
    command: sort data.csv > sorted.csv
    outputs: [sorted.csv]
  - type: llm
    recipe: analyze        # internal:true-Recipe
    prompt: "Fasse sorted.csv zusammen"
    output: summary.md
export:
  - from: summary.md       # export-Quelle ist IMMER lokal (Workspace)
    to: vance:summary.md
  - from: repo             # git-Working-Tree (via git-import geklont)
    to: git:https://github.com/acme/repo.git
    message: "Update from Damogran"   # branch/push/credentialAlias optional
```

## Task-Typen

| type | Feld(er) | tut |
|---|---|---|
| `exec` | `command`, opt. `deadlineSeconds` | Shell im Workspace |
| `python` | `script` **oder** `code`, opt. `deadlineSeconds` | Python-Datei/Inline |
| `js` | `script` **oder** `code` | Workspace-JS (nur Rückgabewert) |
| `llm` | `recipe`, `prompt`, `output` | Single-Shot-LLM → Output-Datei |
| `spawn` | `recipe`, `prompt` | Worker-Prozess (fire-and-forget, neuer Prozess je Run) |
| `agent` | `prompt` (Recipe via `session.recipe`) | Prompt als Turn an den Session-Prozess, blockiert bis Antwort; Output `vance-process:<pid>/<msgId>` |
| `tex-task` | `main` (`.tex`), opt. `engine` | LaTeX → PDF |

## Regeln

- **`outputs:`/`output:`** deklariert, welche Workspace-Dateien als Ergebnis
  erscheinen (rendern in der UI-Output-Region: Markdown/Text/Bild/PDF nach Typ).
  Kein Auto-Import — persistente Ergebnisse gehen über `export:`.
- **Zeitlimit `exec`/`python`**: `deadlineSeconds` (Alias `timeoutSeconds`,
  Default **600**) ist ein **Hard-Kill** — läuft der Befehl länger, wird er
  beendet und der Task schlägt sauber fehl (`status=TIMED_OUT`, **kein**
  verwaister Prozess). Der Compose wartet bis Ende oder Kill; ein schneller
  Befehl kehrt sofort zurück (die Deadline ist nur die Obergrenze).
- **Strukturierte Anzeige**: `outputs: [{ path: data.yaml, as: records }]`
  rendert kanonisch-formatierten Inhalt als Tabelle (`records`) / `tree` /
  `chart` etc. — nur mit explizitem `as:`; ohne bleibt es Text.
- **`vance:`-Pfade**: `vance:hello.tex` ist relativ zum Ordner des compose-
  Dokuments; `vance:/x` ist root-absolut (selbes Projekt); `vance://projekt/x`
  ist cross-project.
- **`import` zuerst, `export` zuletzt.** Ein Task liest, was `import` + frühere
  Tasks hinterlassen haben.
- **`llm` braucht** eine deklarierte Output-Datei; die Antwort landet dort.
- **`js`** ruft `vance.tools.call(...)` mit dem Tool-Set des **gebundenen
  Process**: bei aktiver Chat-Session dessen Tools (ob `file_*` dabei sind, hängt
  vom Chat-Engine/Recipe ab); beim eigenen LLM-`compose_run` dein Process. **Läuft
  der Compose chatlos per Button** (Web-UI, keine Chat-Session), braucht `js` mit
  Tool-Nutzung eine aktive `session:`-Sektion — sonst ist das Tool-Surface **leer**
  (`vance.files.isEnabled()` → false; der Skript-Rückgabewert läuft trotzdem).
  `vance.tools.list()`/`has(name)` zeigen Verfügbares; `vance.files` ist der
  Datei-Adapter (`isEnabled()` prüft, `read`/`readRaw`/`write`/`list` werfen
  sonst). Für garantierte Datei-Erzeugung immer `python`/`exec` (direktes cwd).
- **`spawn`** braucht einen Owner-Process. Beim eigenen `compose_run` hast du
  ihn immer; ein **chatloser Button-Run** braucht eine aktive `session:`-Sektion,
  sonst failt der Task sauber mit *„spawn task requires a process context"*.
  `spawn` erzeugt **je Run einen neuen Prozess** (amnesisch).
- **`agent`** schickt `prompt` als **Turn** an den Session-Prozess (`session.recipe`
  = welcher Agent, z.B. `arthur`). Weil `session.name` **stabil** ist, setzt jeder
  Run **dieselbe** Konversation fort (Kontinuität; `session.clean: true` = frisch).
  Der Task **blockiert bis die Antwort da ist** (Obergrenze `deadlineSeconds`,
  Default 300) und gibt die konkrete Antwort-Message als
  **`vance-process:<pid>/<msgId>`-Output** zurück. Für aufeinander aufbauende
  Agenten-Läufe `agent` statt `spawn` nehmen.
- Fehler stehen im Task-Result (`status: failure`, `error`), nicht als Exception.
- **`target: CLIENT` / `DAEMON`**: läuft gegen das Dateisystem eines Remote-
  Hosts — CLIENT = der verbundene **Foot** (Foot-Session nötig), DAEMON = ein
  benannter **Daemon** (`workspace.name` = Daemon-Name, muss im Projekt verbunden
  sein). Tasks: **nur `exec`** (kein managed Workspace, kein `delete`, kein
  python/tex/js). **`import`/`export` gehen**: `vance:`/`http:` **text-basiert**
  (Import) bzw. `vance:` (Export), und **`git:*`** über das `git` des
  Remote-Hosts per exec (Import = clone/pull, Export = commit/push; fehlt `git`
  dort → Fehler). Nur **binäre** Kopie ist WORK-only, und `credentialAlias`
  (Vault) gilt nicht remote — dort zählen die git-Credentials des Hosts (ssh-Key
  / credential helper). Für „mehrere Shell-Schritte nacheinander auf einem
  Remote-Rechner". Default bleibt `WORK`.
- **Deps (`node`/`python`):** `workspace.options.packages: [numpy, pandas==2.0]`
  (bzw. npm-Specs) installiert die Liste beim Provisioning (pip/npm) — statt erst
  `requirements.txt`/`package.json` importieren zu müssen. Einmalig bei
  Neu-Anlage; ein wiederverwendeter Workspace behält sie (Änderung → `clear: true`).
- **Workspace löschen:** `workspace: { name: x, delete: true }` verwirft den
  benannten Workspace und stoppt (idempotent — fehlt er, ist es ein No-op).
  `delete` ist **terminal**: nicht mit `clear`/`import`/`tasks`/`export`
  kombinierbar (sonst Fehler). `clear: true` dagegen leert + legt leer neu an.

## Beispiel (LaTeX)

```yaml
workspace: { name: paper, type: temp }
import:
  - from: vance:thesis.tex
    to: thesis.tex
tasks:
  - type: tex-task
    main: thesis.tex
    output: thesis.pdf
export:
  - from: thesis.pdf
    to: vance:thesis.pdf
```
