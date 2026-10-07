# Bereich: Mounts

Die Jaglan-Mounts unter `_vance/config/mounts/` — **eine Datei pro
eingehängtem Ordner**. Ein Mount macht ein externes Verzeichnis als
`_ext/<name>/` im Projekt sichtbar: die Dateien bleiben, wo sie sind
(auf der Workstation oder einem Server), und erscheinen trotzdem im
Dokument-Baum.

## Was du hier einstellst

- **Protokoll** — die Art des Mounts. `local` hängt ein Verzeichnis
  der Workstation ein; weitere Protokolle wachsen mit dem Build.
- **Wurzel und Schreibrechte** — beim `local`-Protokoll: welches
  Verzeichnis (`rootDir`) und ob der Brain hineinschreiben darf
  (`writable`). Ein Nur-Lese-Mount zeigt Inhalte, verwirft aber
  Schreibversuche mit einem klaren Fehler.
- **Credential** — Mounts mit Zugangsdaten (Netzwerk-Freigaben)
  tragen den Key als Vault-Referenz.

## Verhalten im Projekt

Die gemounteten Dateien erscheinen unter `_ext/<name>/`. `list()` über
einen Mount ist **vollständig** — es gibt kein verstecktes Dahinter.
Eine fehlende Datei ist ein 404, kein 5xx: der Mount kann defekt sein,
ohne dass er den Dokument-Baum mitreißt.
