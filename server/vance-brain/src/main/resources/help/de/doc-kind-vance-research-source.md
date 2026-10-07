# Research-Quelle

Eine Datei unter `_vance/config/research/<name>.yaml` — die
Konfiguration einer einzelnen Zarniwoop-Quelle. Der Name der Datei
ist der Name, über den der Research-Dispatcher die Quelle wählt.

## Formular

- **Protokoll** — die Art des Zugriffs (`serper`, `tavily`, …). Der
  Katalog der bekannten Protokolle wächst mit dem Build; ein
  unbekannter Wert bleibt als rohe Eingabe erhalten.
- **Endpunkt** — die Base-URL der API.
- **API-Key** — als Vault-Referenz (`{{secret:vault:…}}`) oder als
  Verweis auf die Credential der Setting-Forms. **Nicht** im
  Klartext — die Datei lebt im Projekt.
- **Aktiv** — ausgeschaltet pausiert die Quelle (Fehler und Timing
  messen weiter, Abfragen gehen woanders hin).

Zusätzlich zeigt das Formular die pro Protokoll passenden Felder an —
alles, was es nicht rendert, bleibt im YAML erhalten.
