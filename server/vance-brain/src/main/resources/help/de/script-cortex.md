# Script Cortex

Werkbank für kurze JavaScript-Snippets, die in der GraalJS-Sandbox des
Brains laufen. Geeignet für einmalige Berechnungen, Debug-Skripte zum
Wegwerfen und die Orchestrator-Scripte aus Hactar.

## Dateien

Script Cortex ist ein **File-Explorer über das komplette Projekt** —
es zeigt alle Dokumente, nicht nur eine reservierte Zone. Du kannst
JavaScript-Snippets überall ablegen, z.B.:

- **`scripts/`** — Default-Bucket für eigenständige Skripte.
- **`skills/<name>/scripts/`** — Skill-gebundene Scripte, die als
  virtuelle Tools eingehängt werden.
- Jeder andere Pfad — Script Cortex zwingt keine Struktur auf.

Die Ausführbarkeit hängt allein an der **Extension**, nicht am
Ablage-Ort:

- **`.js`** — ausführbares JavaScript. Hat einen **Execute**-Button.
- **`.json`** — statische Daten. Wird später per
  `vance.script.load(pfad)` aus anderen Scripten geladen.
- **`.md`** — Markdown-Notizen. Nützlich um ein Script-Bundle zu
  dokumentieren.
- Alles andere — wird als Plain-Text geöffnet, kein Execute.

Ordner sind virtuell: der Pfad `utils/math/sum.js` legt automatisch
den Ordner `utils/math/` in der Sidebar an. Beim Neu-Anlegen wird
`scripts/` als Default vorgeschlagen; du kannst jeden anderen
Projekt-Pfad eintippen.

## Leeres Script — Standard-Vorlage

Eine neue `.js`-Datei startet leer. Die typische Form ist eine
sofort-aufgerufene Funktion (IIFE), die einen Wert zurückgibt:

```js
/**
 * @description was dieses Script macht
 * @timeout     5s
 */
(function () {
    console.log('hallo aus script-cortex');
    var ergebnis = args.x + args.y;
    return { ok: true, value: ergebnis };
})();
```

Verfügbare Bindings:

- **`args`** — globale Variable mit dem JSON-Objekt, das im
  Execute-Dialog eingetippt wurde (z.B. `{ "x": 3, "y": 4 }`).
- **`console.log/info/warn/error`** — Debug-Output schreiben. Jeder
  Call erscheint live im Output-Bereich des Execute-Dialogs. Derselbe
  Text wird im Execution-Log-Buffer persistiert (5 Min Retention,
  10k-Zeilen-Ring).
- **`vance.log.info/warn/error(message, fields?)`** — strukturierter
  Logger, geht **beides**: SLF4J-Server-Log und Execute-Dialog. Der
  `fields`-Parameter ist ein optionales Daten-Objekt, das hinter der
  Message als `{key=value}` angehängt erscheint.
- **Rückgabewert** — letzter Expression-Value. Primitives bleiben
  Primitives, JS-Objekte werden in JSON-freundliche Maps übersetzt.
  Erscheint im grünen **Result**-Kasten wenn das Script fertig ist.

## Debug-Output: das Wesentliche

```js
console.log('einfacher String');
console.log('mit args:', args);             // gibt das args-Objekt aus
console.warn('das ist eine Warnung');
console.error('das ist ein Fehler');
```

Alle vier Kanäle landen im selben Output-Bereich, getagged mit dem
Kanalnamen (`[log]`, `[info]`, `[warn]`, `[error]`).

Bei länger laufenden Computations Fortschritts-Marker loggen, damit du
siehst dass das Script nicht hängt:

```js
for (var i = 0; i < N; i++) {
    if (i % 100 === 0) console.log('fortschritt', i, '/', N);
    // ...
}
```

## Header (JSDoc-Style, oben in der Datei)

Optional, aber praktisch:

- `@timeout 30s` — Wall-Clock-Limit (Default 30s, max 1h).
- `@description ...` — taucht in der Script-Liste auf.

Header müssen im **ersten** JSDoc-Block der Datei stehen. Falsche
Tag-Werte schlagen fail-fast vor dem Eval fehl.

## Validieren

Zwei Buttons im rechten Panel (wenn Hilfe geschlossen ist):

- **Quick Validate** — Parser-only-Check. Findet Syntax-Errors,
  unschlossene Strings, kaputte Header. Kostenlos, instant.
- **Deep Validate (LLM)** — schickt das Script an ein kleines Modell,
  das verdächtige Patterns flaggt: Endlosschleifen, Blocking-I/O,
  fehlende Returns, Header-Anomalien. Cached pro Content-Hash, also
  bei unverändertem Script kommt das Ergebnis instant aus dem Cache.

Der Deep-Review-Cache überlebt Reloads — beim erneuten Öffnen einer
Datei sagt das Banner entweder *"matches current"* (grün) oder
*"content has changed since"* (gelb).

## Execute

Der Execute-Dialog fragt nach einem `args`-JSON-Objekt (Default `{}`).

- **Run** startet das Script asynchron. Die UI zeigt den Live-Log,
  der State-Badge wechselt `starting → running → finished` (oder
  `failed` / `cancelled`).
- **Cancel** unterbricht ein laufendes Script — der GraalJS-Context
  wird von außen geschlossen, der Worker-Thread terminiert.
- Output über 10 000 Zeilen wird ring-truncated; die ältesten Zeilen
  fallen zuerst raus.

## Generieren / Verbessern (Hactar)

Der **🧠 Hactar**-Button öffnet einen Prompt-Panel. Eintippen
was das Script machen soll, **Generate** klicken. Das Brain spawnt
einen Deep-Thought-Prozess, der den Code draftet und validiert; bei
`DONE` kannst du das Ergebnis in den aktiven Tab übernehmen.

Wenn *"Include current script as context"* angehakt ist, wird die
existierende Datei an den Prompt angehängt — nützlich für „mach das
schneller" oder „füge hier Error-Handling ein"-Refactors.

## Tool-Aufrufe

Script Cortex hat Zugang zum **vollen Tool-Set** des Projekts —
dasselbe, das auch Engines wie Arthur oder Eddie sehen. Aufruf:

```js
var docs = vance.tools.call('documents_list', { projectId: vance.context.projectId });
console.log('docs:', docs);
```

Die Hactar-Generierung bekommt dieselbe Tool-Liste in den
Drafting-Prompt eingespeist — das LLM weiß also welche Tool-Namen es
mit korrekten Signaturen aufrufen darf. Per-Tool-Permissions werden
zur Laufzeit im Tool-Handler durchgesetzt (kein Tenant-Escalation
möglich).

Wenn du **keine** Tool-Aufrufe willst (reines Rechen-Snippet), ruf
einfach keine — der Surface ist da, aber nicht erzwungen.

## Was v1 nicht macht

- **Kein Cross-Script-`require`.** Laden anderer Script-Cortex-Dateien
  aus JS kommt in einem Follow-up.
- **Kein Multi-User-Edit-Locking.** Last write wins — dieselbe Datei
  immer nur in einem Browser-Tab offen halten.
