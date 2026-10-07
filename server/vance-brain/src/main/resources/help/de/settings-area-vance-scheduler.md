# Bereich: Scheduler

Die Ursa-Scheduler unter `_vance/scheduler/` — **eine Datei pro
zeitgesteuertem Auslöser**. Ein Scheduler feuert ein Recipe, einen
Workflow oder ein Skript zu einem Zeitpunkt, den du wählst.

## Was du hier einstellst

- **Was passieren soll** — Beschreibung, Trigger-Ziel (Recipe, Workflow
  oder Skript) und optional der Prompt pro Lauf. **Genau eines** der
  drei Ziele — der Loader lehnt alles andere ab.
- **Wann er feuert** — ein Cron-Ausdruck (wiederkehrend) oder ein
  konkreter Zeitpunkt (`at`, einmalig). **Genau eines** von beiden.
- **Zeitzone** — IANA-Zone; leer = UTC.
- **Aktiv** — pausierte Scheduler bleiben registriert, feuern aber
  nicht.

## Abgrenzung

Der Bereich ist das **Konfigurations-Inventar**. Feuern von Hand und
das Ereignis-Protokoll leben im Insights-Tab „Scheduler" — beide
lesen dieselben Dokumente, es gibt keine zweite Definition.

Der Eintrag öffnet das Scheduler-Formular inline (dieselbe Fläche wie
im Cortex-Dokument-Tab).
