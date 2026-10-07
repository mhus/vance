# Bereich: KI-Modelle

Der Modell-Katalog unter `_vance/model/` — eine Datei pro Modell plus
eine `*_provider.yaml`-Begleitdatei pro Provider. Hier stellst du ein,
**welche Modelle der Brain kennt und wie er sie anspricht**: Kontext-
und Output-Limits, Fähigkeiten (Vision, PDF, Large), Timeouts und
Preise für die Quoten-Abrechnung.

## Namen tragen Struktur

Der Create-Dialog nimmt die Struktur im Namen auf:

- **`provider`** (ohne Schrägstrich) — legt die Provider-Begleitdatei
  `_provider.yaml` an: wie der Brain mit dem Anbieter redet.
- **`provider/modell`** — legt das Modell-Dokument an. `:` im Namen
  wird zur `wireName`-Zeilenkette, `/` verschachtelt Verzeichnisse.

Credentials und Provider-Typ gehören in die **Setting-Forms**
(llm-provider-*), nicht hier — hier wohnen Modell-Eigenschaften.

## Kaskade

Projekt überschreibt Mandant, Feld für Feld: ein **leeres Feld fällt
auf den äußeren Wert zurück**, es erbt nicht „hinein". Unbekannte Keys
bleiben beim Runden erhalten — das Formular verschweigt nichts.

Achtung: `_vance/model-auto/` ist **Discovery-eigentümlich** und
erscheint hier bewusst nicht — dort schreibt das Modell-Discovery, nie
von Hand.
