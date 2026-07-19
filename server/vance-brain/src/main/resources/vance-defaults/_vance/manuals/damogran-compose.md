---
triggers: compose, damogran, workspace, workspace ausführen, prepare files, exec im workspace, kompilieren, latex, tex nach pdf, python analyse, dateien importieren, git clone, git pull, git push, git commit, repo klonen, batch
summary: Provision a named workspace, import documents, run a linear list of tasks (exec/js/python/llm/tex), export results — via compose_run.
requires-tools: compose_run
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
(inline). Antwort: `{ success, workspace, tasks: [{status, outputs, error?}] }`.
Der Lauf ist **linear** und **hält beim ersten fehlgeschlagenen Task**.

## Manifest

```yaml
title: Report bauen        # optional; im View über dem Run-Button gezeigt
description: Sortiert + fasst zusammen   # optional
workspace:
  name: my-work            # benannter Workspace (überlebt Re-Runs in der Session)
  type: temp               # temp | git | node | python
  clear: false             # true = vorher leeren, dann leer neu anlegen
  target: WORK             # WORK (default) | CLIENT (exec-only, Foot) | DAEMON (exec-only)
  options: { repoUrl: … }  # nur git: repoUrl/branch
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
| `spawn` | `recipe`, `prompt` | Worker-Prozess (fire-and-forget) |
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
  vom Chat-Engine/Recipe ab); **chatlos** ein projekt-scoped Carrier-Process mit
  den `file_*`/`exec_*`-WorkTarget-Tools (schreibt in den Compose-Workspace).
  `vance.tools.list()`/`has(name)` zeigen Verfügbares; `vance.files` ist der
  Datei-Adapter (`isEnabled()` prüft, `read`/`readRaw`/`write`/`list` werfen
  sonst). Für garantierte Datei-Erzeugung immer `python`/`exec` (direktes cwd).
- Fehler stehen im Task-Result (`status: failure`, `error`), nicht als Exception.
- **`target: CLIENT` / `DAEMON`**: läuft gegen das Dateisystem eines Remote-
  Hosts — CLIENT = der verbundene **Foot** (Foot-Session nötig), DAEMON = ein
  benannter **Daemon** (`workspace.name` = Daemon-Name, muss im Projekt verbunden
  sein). Tasks: **nur `exec`** (kein managed Workspace, kein `delete`, kein
  python/tex/js). **`import`/`export` gehen** — aber **text-basiert** und nur
  `vance:`/`http:` (Import) bzw. `vance:` (Export); `git:*` und Binärdateien sind
  WORK-only. Für „mehrere Shell-Schritte nacheinander auf einem Remote-Rechner".
  Default bleibt `WORK`.
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
