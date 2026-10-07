# Bereich: Feed-Quellen

Die Centauri-Feed-Quellen unter `_vance/config/feeds/` — **eine Datei
pro Feed** (RSS/Atom und ähnliche). Der Centauri-Dienst mischt die
Cursors aller Quellen zu einem Strom: neueste Einträge zuerst,
Nachfilter greifen erst nach dem Über-Fetch.

## Was du hier einstellst

- **Endpunkt und Protokoll** — die Adresse des Feeds.
- **Credential** — falls der Feed Zugangsdaten braucht, als
  Vault-Referenz.
- **Aktiv** — pausierte Feeds werden nicht mehr abgeholt, der Cursor
  bleibt stehen.
- **Leser-Identität** — Centauri liest als Pseudonym; die Konfiguration
  steuert, wie sich der Abruf gegenüber dem Feed-Ausgeber darstellt.

## Scopes

Feeds gelten pro Projekt; der Mandant stellt Vorgaben, ein Projekt
überschreibt je Name.

Dieser Bereich stammt vom **Centauri-Addon** — ist es nicht installiert,
fehlt die Fläche hier komplett.
