# Mount-Definition

Eine Datei unter `_vance/config/mounts/<name>.yaml` — ein einzelner
Jaglan-Mount. Der Name der Datei ist der Name des Mounts und damit
der Ordner unter `_ext/`, in dem er erscheint.

## Formular

- **Protokoll** — die Art des Mounts (`local` …). Unbekannte Werte
  bleiben als rohe Eingabe erhalten.
- **Endpunkt** — bei Netzwerk-Protokollen die Adresse der Freigabe.
- **API-Key** — Zugangsdaten als Vault-Referenz
  (`{{secret:vault:…}}`), nie im Klartext.
- **`rootDir`** (nur `local`) — das Wurzelverzeichnis auf der
  Workstation. Absolut oder relativ zum Workspace.
- **`writable`** (nur `local`) — `false` (Default) macht den Mount
  Nur-Lese: lesen funktioniert, schreiben scheitert mit einem
  klaren Fehler statt still verloren zu gehen.
- **Aktiv** — ausgeschaltet verschwindet der Mount aus dem Baum,
  ohne die Datei zu löschen.

Alles, was das Formular nicht rendert, bleibt im YAML erhalten.
