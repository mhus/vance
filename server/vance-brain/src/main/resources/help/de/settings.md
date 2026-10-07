# Einstellungen

Diese Seite bündelt **alles Konfigurierbare** eines Scopes an einem Ort.
Was du hier änderst, greift sofort — der Brain liest die Dokumente bei
der nächsten Nutzung neu.

## Scopes — die drei Ebenen

- **☺ Meine Einstellungen** — deine persönliche Ebene (`_user_<login>`).
- **⌂ Mandant** — mandantenweite Vorgaben (`_tenant`). Gilt für alle
  Projekte, solange nichts Inneres sie überschreibt.
- **Projekte** — wähle ein Projekt in der Liste. Projekt-Einstellungen
  überschreiben die des Mandanten; gleiche Namen überschreiben, andere
  ergänzen.

Die URL ist der Zustand: Scope, Tab und offener Eintrag stehen in der
Adresse — ein Lesezeichen oder Zurück-Knopf reproduziert die Ansicht.

## Tabs

- **Bereiche** — Konfigurations-Dokumente je Thema: Modelle, Recipes,
  Scheduler, Workflows, Hooks, Guards, Prompts, Themes, Wizards,
  Quellen und Mounts. Je Bereich eine Liste mit Hinzufügen/Löschen; ein
  Klick öffnet den Eintrag inline.
- **Settings** — die typisierten Setting-Forms (Key/Value mit
  Kaskade-Anzeige: „effektiv aus Mandant").
- **Projekt/Mandant** — Verwaltung: Eigenschaften, Sprache,
  Session-Gruppen, Kits, Replikation.
- **Erweitert** — der rohe Key/Value-Editor über alle Ebenen.

## Eintrag bearbeiten

- **Anwenden** speichert und bleibt auf dem Eintrag.
- **Speichern** speichert und springt zurück zur Liste.
- **↗** öffnet dasselbe Dokument im Cortex — dem vollen Editor mit
  Notizen, Eigenschaften und Versionsarchiv.

## Hilfe

Der rechte Bereich zeigt kontextabhängige Hilfe: pro Bereich eine
Einführung, pro offenem Eintrag die Doku seiner Dokumentart. Sie
wechselt automatisch mit, was du offen hast.
