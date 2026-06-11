# Cortex

Eine vereinte Arbeitsumgebung, die **Chat, Dokumente und Ausführung**
für ein Projekt zusammenbringt. Über *In Cortex öffnen* aus jedem
Chat erreichbar; der Chat behält seinen Verlauf, der Projekt-File-
Tree erscheint links, und derselbe Chat sitzt im rechten Panel
neben dem Dokument, an dem du arbeitest.

## Layout

- **Links** — File-Tree des Projekts.
- **Mitte** — ein oder mehrere offene Dokumente als Tabs. Der aktive
  Tab rendert mit der richtigen View für seine Kind: Liste,
  Checkliste, Diagramm, Script-Editor, …
- **Rechts** — zwei Tabs:
  - **Chat** — die Agent-Konversation aus der du gekommen bist.
  - **Help** — dieses Panel. Inhalt ändert sich pro aktiver Doc-Kind.

## Dokument-Tabs

Jedes geöffnete Dokument bekommt einen Tab. Tabs werden über
Cortex-Besuche hinweg erinnert — morgen denselben Chat in Cortex
öffnen, dieselben Tabs sind wieder da. *✕* schließt einen Tab;
unsaved Edits werden vorher noch geflusht.

Die Kopfzeile über jedem Tab zeigt:

- **⟳** — Server-Reload (verwirft lokale Änderungen, fragt vorher).
- **pfad/zur/datei** — voller Pfad innerhalb des Projekts.
- **View / Edit** — Toggle für typisierte Dokumente (Listen, Charts,
  Trees, …). *View* zeigt die gerenderte Form, *Edit* fällt auf den
  Roh-Source im CodeEditor zurück.
- **↗** — öffnet die vollständige Properties-Seite in einem neuen
  Tab (Titel, MIME, Tags, RAG-Mode, Archive, …).
- **●** — erscheint während es ungespeicherte Edits gibt.
- **[binding-id] mime-type** — Debug-Pill der zeigt welcher Renderer
  matched. Hover für die volle *(kind, mime)*-Auflösung.

## Chat-Binding

Genau ein Dokument ist chat-bound — die Edit-Tools des Agenten
zielen darauf. Das bound Document zeigt sich in der Topbar als
*🔗 pfad/zur/datei*; Click zum aktuellen Tab binden. *In Cortex
öffnen* bindet automatisch das erste geöffnete Dokument.

Der Agent kann:

- das gebundene Dokument lesen (`cortex_read`),
- die aktuelle User-Selection abfragen (`cortex_get_selection`),
- exakte Strings ersetzen (`cortex_edit`), Text anhängen
  (`cortex_append`) oder die Datei neu schreiben (`cortex_write`).

Alle Edits werden im Browser gestaged; Auto-Save persistiert sie
nach 2 Sekunden Pause. Während ein Tool läuft, zeigt die Topbar
*agent editing…*; Tools bleiben registriert (kein Hard-Lock), aber
parallele Tastatureingaben auf derselben Range sollte man
vermeiden.

## Save

Auto-Save greift zwei Sekunden nach dem letzten Edit. Tab-Wechsel
oder Seite schließen flusht pending Writes synchron. Der *●* Dot
verschwindet wenn alles auf Platte ist.

## Run (wenn anwendbar)

Skripte (`.js` / `.mjs` / `.mjsh`) bekommen einen **▶ Run** Button
neben dem Pfad. Args ist ein einzeiliges JSON-Object (Default
`{}`). Das Log-Panel fährt unter dem Editor hoch und streamt
stdout / stderr live; *Cancel* bittet den Worker abzubrechen.
Das Result erscheint unter dem Log sobald das Skript fertig ist.

Wenn du gerade getippt und Run gedrückt hast: der Editor flusht
zuerst auf den Server, damit der executete Code dem entspricht
was du siehst.

## Tipps

- Mehrere Tabs: der View/Edit-Toggle ist pro-Tab und resetted auf
  *View* bei jedem Tab-Switch.
- Der Help-Tab reflektiert immer den **aktiven** mittleren Tab —
  zwischen Docs wechseln und die Hilfe folgt.
- Der Chat überlebt einen Switch auf Help; zurückwechseln und der
  Message-Buffer ist noch da.
