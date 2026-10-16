# Bereich: Server-Tools

Die Tools, die der Brain selbst ausführt — MCP-Server, REST-APIs,
Mailkonten, Skripte — unter `_vance/server-tools/`, **eine Datei pro
Tool**. Der Dateiname ist der Tool-Name.

## Welche Tools du siehst

Tools lösen über eine Kaskade auf: **Projekt → Mandant → mitgeliefert**.
Die Liste zeigt zuerst die Tools des gewählten Scopes, darunter — mit
ihrer Herkunft markiert — die, die er nur erbt:

- **Mandant** — im Mandanten-Scope definiert, gilt in jedem Projekt.
- **Mitgeliefert** — kommt mit dem Server.

Ein geerbtes Tool wird nicht an Ort und Stelle bearbeitet. **Hier
überschreiben** kopiert es in diesen Scope; ab dann gilt hier die Kopie,
das Original bleibt für alle anderen unverändert. Wer die Kopie löscht,
bekommt das geerbte Tool zurück. Um ein Tool in einem Projekt
auszublenden: überschreiben und **Aktiv** abschalten.

## Tool hinzufügen

**Hinzufügen** fragt nach einem Namen in snake_case (`notion`,
`jira_rest`). Das neue Tool startet als **abgeschalteter** MCP-Server:
Server eintragen, dann einschalten. Jeden anderen Typ wählst du im
Formular.

Server-Tools gibt es für den Mandanten und für Projekte — eine
persönliche Ebene gibt es nicht.
