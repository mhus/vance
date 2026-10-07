# Feed-Quelle

Eine Datei unter `_vance/config/feeds/<name>.yaml` — die Konfiguration
eines einzelnen Centauri-Feeds. Der Dateiname ist der Name, über den
der Feed-Dienst die Quelle verwaltet.

## Formular

- **Protokoll** — Art des Feeds (RSS, Atom …).
- **Endpunkt** — die URL des Feeds.
- **API-Key** — Zugangsdaten als Vault-Referenz
  (`{{secret:vault:…}}`), nie im Klartext.
- **Aktiv** — pausiert den Feed, ohne ihn zu löschen.

Alles, was das Formular nicht rendert (z.B. die Leser-Identität und
Protokoll-Extras), bleibt im YAML erhalten und wird weiterhin
verwendet.
