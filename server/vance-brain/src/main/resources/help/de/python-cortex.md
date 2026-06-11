# Python in Cortex

Werkbank für Python-Snippets, die **server-seitig** auf dem Brain-Host
in der projekt-eigenen venv laufen. Gleiches Cortex-Layout wie für
JavaScript (File-Tree, Tabs, Chat) — der **▶ Run Python** Button
startet einen echten `python`-Subprozess, keine Sandbox.

## Dateien

Jede `.py`-Datei im Projekt-Tree ist runnable. Cortex erkennt den
Python-Runner automatisch — die Extension schlägt den MIME-Type.

- **`scripts/`** — Default-Bucket für eigenständige Snippets.
- **`skills/<name>/scripts/`** — Skill-gebundener Code, wo das
  sinnvoll neben dem Skill liegt.
- Jeder andere Pfad — Cortex zwingt keine Struktur auf.

Andere Extensions (`.json`, `.yaml`, `.md`, …) öffnen als reine
Dokumente, kein Run-Button.

## Wie es läuft

Der erste `Run`-Klick auf die erste `.py`-Datei eines Projekts macht
einen einmaligen Bootstrap:

- Legt ein projekt-eigenes Python-RootDir mit Label `_python` an.
- Provisioniert eine frische `.venv/` mit dem Python-Interpreter,
  den der Brain konfiguriert hat.

Folgende Runs nutzen dieselbe venv weiter — alles was du per
`python_install` (vom LLM aus) oder via Inline-Metadata (siehe unten)
installiert hast, bleibt verfügbar. Jeder Run schreibt den aktuellen
Doc-Inhalt als `_inline_<timestamp>.py` ins RootDir und startet:

```
.venv/bin/python <flags> <transient.py> <args>
```

## Inline-Dependencies (PEP 723)

Der Cortex-Runner liest [PEP 723](https://peps.python.org/pep-0723/)
Inline-Script-Metadata — ein Kommentar-Block am Dateianfang, der die
benötigten Third-Party-Pakete deklariert. Run greift den Block auf,
installiert fehlende Pakete in die Projekt-venv und führt das Skript
dann aus. Mit Hash-Cache: ungeänderte Skripte installieren nicht
neu.

```python
# /// script
# dependencies = [
#   "requests",
#   "rich >= 13",
# ]
# ///

import requests
from rich import print
print(requests.get("https://api.github.com").json())
```

Anmerkungen:

- V1 verwertet nur das Feld `dependencies`. Andere PEP-723-Felder
  (`requires-python`, benannte Labels) werden geparst und ignoriert —
  die Python-Version der venv ist die vom Brain konfigurierte.
- Hash-Marker liegt in der venv als `.vance_inline_deps_hash`.
  Dependency-Liste ändern → nächster Run installiert neu; sonst wird
  pip übersprungen.
- Ein gescheitertes `pip install` lässt den Marker unverändert; der
  nächste Run versucht's nochmal.
- Für längerlebige Multi-File-Projekte besser das LLM-Tool
  `python_install` mit `pyproject.toml` — Inline-Deps sind für
  self-contained One-Shot-Skripte gedacht.

## Args

Das `{}`-Feld in der Toolbar ist ein JSON-Object. Für Python wird es
vor dem Start zu Shell-Args flach gemacht — jeder Top-Level-Key wird
zu einem `key=value`-Token hinter dem Befehl. Beispiel:

```json
{ "n": 10, "verbose": true }
```

wird zu `python script.py n=10 verbose=true`. Im Skript selbst
parsen:

```python
import sys
for arg in sys.argv[1:]:
    key, _, value = arg.partition('=')
    print(f"got {key!r}={value!r}")
```

Wenn du eine reine Positions-Arg-Liste willst, kommt das in einer
späteren Variante des Toolbar-Inputs — V1 bleibt bei der
Key-Value-Konvention.

## Output

stdout und stderr streamen beide ins Log-Panel unter dem Editor,
mit `[stdout]` / `[stderr]` getaggt. Der Cortex-Python-Runner
**pollt** alle ~1.5 s und ersetzt den Log-Buffer mit dem aktuellen
Snapshot — es gibt keinen WebSocket-Push für Python (im Unterschied
zum JS-Runner). Kurze Skripte fühlen sich live an; lange Skripte
bekommen jeden Polling-Tick neue Zeilen.

Wenn der Prozess endet, springt das Badge auf `finished` (Exit-Code
0) oder `failed` (Exit-Code ≠ 0). Das Result-Feld zeigt bei Erfolg
`{ exitCode: N }` — Python hat keinen "Return-Value" wie der JS-
Runner mit dem letzten Ausdruck, nur stdout + Exit-Code.

## Cancel

Der `■ Cancel`-Button killt den Subprozess via
`ExecManager.kill`. Best-Effort: Forks oder Threads, die dein Skript
gestartet hat, brauchen ggf. eigene Cleanup-Pfade; der Hauptprozess
bekommt SIGKILL sobald der Watchdog ihn fängt.

## Erst speichern

Cortex flusht ungespeicherte Edits **vor** dem Run an den Server —
der Brain lädt den Doc-Body aus MongoDB, nicht aus deinem Browser-
Buffer. Wenn du gerade getippt und auf Run gedrückt hast: die
gespeicherte Version ist das was ausgeführt wird.
