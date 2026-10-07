# Bereich: Research

Die Zarniwoop-Quellen unter `_vance/config/research/` — **eine Datei
pro Such-Quelle**. Der Research-Dispatcher wählt anhand dieser
Dokumente, welche externen Dienste (Suchmaschinen, APIs) befragt
werden und mit welchen Credentials.

## Was du hier einstellst

- **Protokoll** — die Art der Quelle (z.B. `serper`, `tavily`). Ein
  Protokoll, das dieser Build nicht kennt, wird als roher Wert
  weitergegeben statt verstellt zu werden.
- **Endpunkt und Credential** — Base-URL und API-Key je Quelle. Der
  Key gehört als Vault-Referenz hinein (`{{secret:vault:…}}`), nicht
  im Klartext.
- **Aktiv** — eine Quelle lässt sich pausieren, ohne sie zu löschen.

## Scopes

Quellen gelten pro Projekt; der Mandant (`⌂`) stellt Vorgaben, die
ein Projekt je Name überschreibt. Neue Namen ergänzen die Liste.

Die Liste hier filtert nach der Art der Quelle — nur
Research-Quellen, nichts anderes aus dem Ordner.
