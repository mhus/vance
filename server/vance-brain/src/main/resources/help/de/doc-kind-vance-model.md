# Modell-Dokument

Eine Datei unter `_vance/model/<provider>/<slug>.yaml` — ein konkretes
Modell des Katalogs: Identität, Limits, Fähigkeiten und Preise. Der
Dateiname ist der Katalog-Schlüssel; `:` im Namen wurde beim Anlegen
zur `wireName`-Zeile im Body.

## Formular

- **Identität** — `displayName` (angezeigter Name), `wireName` (wie der
  Anbieter das Modell heißt; Pflicht, wenn der Dateiname keine
  Struktur trägt), `size` (Grobklasse für die Modellauswahl;
  „custom" wenn die Standardklassen nicht passen).
- **Limits** — `contextWindowTokens` und `defaultMaxOutputTokens`
  steuern, wie viel der Brain in ein Fenster bzw. eine Antwort packt;
  `timeoutSeconds` begrenzt einen einzelnen Call.
- **Preise** — `pricingInputPerMTok` / `pricingOutputPerMTok` (pro
  Million Tokens, in `pricingCurrency`) speisen die Quoten-Abrechnung.
  Leere Preise gelten als „kostenlos/nicht abrechenbar".

**Zahlen sind Zahlen:** numerische Felder schreibt das Formular als
YAML-Zahlen — ein quoted String (`"131072"`) würde vom Katalog still
auf den Default zurückfallen.

## Kaskade und Teil-Deckung

Projekt überschreibt Mandant **pro Feld**: ein geleertes Feld verwirft
den Schlüssel und erbt vom äußeren Layer. Andere Keys im YAML bleiben
unangetastet — das Formular bearbeitet nur, was es anzeigt.
