# Provider-Begleitdatei

Die `_provider.yaml` unter `_vance/model/<provider>/` — der Kontakt-
Datensatz eines Modell-Anbieters. Sie sagt dem Brain, **über welches
Protokoll und welchen Endpunkt** er die Modelle dieses Providers
anspricht; die einzelnen Modelle wohnen daneben als eigene Dokumente.

## Pflichtfelder

- **`wireType`** — das Draht-Protokoll des Anbieters (z.B. `openai`,
  `anthropic`, `google`, `ollama`, `cohere`, …). **Hard requirement**:
  fehlt es, weigert sich der Katalog für jedes Modell dieses
  Providers — das Speichern blockiert mit einem Fehler statt es still
  zu schlucken.
- **`baseUrl`** — der API-Endpunkt des Anbieters.

## Credentials

API-Keys gehören **nicht** in diese Datei, sondern in die Setting-Forms
(llm-provider-*) oder den Vault (`{{secret:vault:…}}`). Eine
Provider-Begleitdatei mit Klartext-Key landet nicht im Git — aber auch
nicht im Klartext im Brain.

## Kaskade

Projekt überschreibt Mandant: eine lokale `_provider.yaml` ersetzt die
des Mandanten komplett pro Feld — leere Felder erben vom äußeren Layer.
