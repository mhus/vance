---
audience: eddie
triggers: kit, kit_install, kit_update, kit_status, kit installieren, install kit, kit update, vance-kits, prune kit, vault password, project kit, kit aus repo, kit branch, kit commit, kit lokal
summary: How Eddie installs, updates, and inspects project kits — default vance-kits.git source, branch/commit pinning, vault passphrases, and prune semantics.
---
# Wie ich ein Kit in ein Projekt installiere

Ein **Kit** ist ein Bundle aus Skills, Recipes, Dokumenten, Settings
und Tool-Definitionen, das ein Projekt in einen ausgestatteten Zustand
bringt. Statt jedes Stück einzeln anzulegen, ziehe ich ein Kit aus
einem Git-Repo (oder einem lokalen Pfad) und lasse die `KitService`
die Inhalte ins Projekt kopieren — inkl. Inherit-Chain, falls das Kit
auf einem anderen aufbaut.

Default-Quelle: <https://github.com/mhus/vance-kits.git>. Da liegen
die offiziell gepflegten Kits in eigenen Unterordnern; per `path`-Param
zeige ich auf das passende.

## Voraussetzung

Ich brauche ein **Ziel-Projekt** — ein normales User-Projekt, kein
System-Projekt (`_vance` oder `_user_<login>`). Ohne `project`-Param
nehme ich das aktuell aktive Projekt (`project_current`).

Pro Projekt darf nur **ein** Kit aktiv sein. `kit_install` schlägt
fehl, wenn schon eines drin sitzt — dann ist `kit_update` der richtige
Weg. Wenn ich ganz wechseln will: erst Manifest entfernen
(`kit_apply` mit leerem Manifest oder Admin-Pfad), dann neu
installieren.

## Installation aus dem Default-Repo

Standardfall — ein Kit aus `vance-kits` ziehen:

```
invoke_tool(
  name = "kit_install",
  params = {
    "url":     "https://github.com/mhus/vance-kits.git",
    "path":    "writing-essay",        // Unterordner im Repo
    "branch":  "main",                  // optional, default main
    "project": "lissabon-erdbeben"      // optional, sonst aktuell
  }
)
```

`path` ist hier wichtig — `vance-kits.git` enthält mehrere Kits
nebeneinander; ohne `path` würde ich das Repo-Root nehmen und das
ist meistens nicht das, was ich will. Wenn der User „installier mir
das Essay-Kit ins Projekt" sagt, steht der Sub-Pfad bei mir.

## Installation aus einem anderen Git-Repo

Genau gleich — andere URL:

```
invoke_tool(
  name = "kit_install",
  params = {
    "url":    "https://github.com/some-org/internal-kits.git",
    "path":   "audit-pack",
    "token":  "ghp_…",                 // bei privaten Repos
    "commit": "abc1234"                 // optional, pin SHA
  }
)
```

`commit` schlägt `branch` — wenn ich reproduzierbar pinnen will, setze
ich den Commit-Hash.

## Installation aus einem lokalen Pfad

Auch File-URLs und absolute Pfade funktionieren. Nützlich, wenn der
User selbst gerade an einem Kit baut und es lokal testen will:

```
invoke_tool(
  name = "kit_install",
  params = {
    "url":  "file:///Users/hummel/dev/my-kit",
    "path": "kit-root"                  // optional, wenn es Unterordner gibt
  }
)
```

Oder direkt als absoluter Pfad:

```
invoke_tool(
  name = "kit_install",
  params = { "url": "/Users/hummel/dev/my-kit" }
)
```

## Vault-geschützte Settings

Wenn das Kit `PASSWORD`-Settings mitbringt, brauche ich eine
Passphrase, damit `KitService` sie verschlüsselt ablegt:

```
params = {
  "url":            "...",
  "vault_password": "<die-passphrase-vom-user>"
}
```

Die Passphrase erfrage ich vom User direkt — niemals raten.

## Status anschauen

Bevor ich update oder neu installiere, frage ich `kit_status`, um zu
sehen, was gerade drin ist:

```
invoke_tool(
  name   = "kit_status",
  params = { "project": "lissabon-erdbeben" }
)
```

Ich sehe: aktiver Kit-Name, Source-URL, gepinnter Commit, Liste der
Artefakte (Documents, Skills, Recipes, Settings, Tools). Wenn der
User „was haben wir installiert?" fragt, ist das mein Tool.

## Kit aktualisieren

Ich rufe `kit_update`, wenn der User auf eine neue Version will oder
wenn upstream Änderungen gemacht hat:

```
invoke_tool(
  name = "kit_update",
  params = {
    "project": "lissabon-erdbeben"
    // url/path/branch/commit werden aus dem bestehenden Manifest
    // übernommen, wenn ich nichts überschreibe
  }
)
```

Wenn ich auf einen anderen Branch oder Commit wechseln will, gebe ich
den entsprechenden Param mit:

```
params = {
  "branch": "v2",
  "prune":  true                       // entfernt verwaiste Artefakte
}
```

`prune=true` löscht Artefakte, die im alten Manifest waren, im neuen
aber nicht mehr — sonst bleiben sie als Karteileichen liegen. Default
ist `false` (sicher), aber wenn der User „mach sauber" sagt, schalte
ich's an.

## Wann ich frage statt installiere

- Wenn der User nur „installier ein Kit" sagt, ohne Quelle —
  ich nenne die Default-Quelle (`vance-kits.git`) und liste ihm via
  Web-Fetch die verfügbaren Sub-Ordner, lasse ihn wählen.
- Wenn das Projekt schon ein Kit hat — ich beschreibe was drin ist
  via `kit_status` und frage, ob `kit_update` reicht oder ein
  Replace gewollt ist.
- Wenn ein Vault-Password fehlt — frage ich danach, statt zu raten
  oder den Call mit Leer-String abzuschießen.

## Was ich nicht mache

- Kein `kit_install` ins `_user_<login>`-Projekt. Das ist mein
  Hub-Arbeitsbereich, nicht für Kits gedacht.
- Kein automatisches Update beim Start. Der User entscheidet, wann
  upstream Änderungen reinkommen.
- Kein Custom-Kit on-the-fly bauen. Wenn der User „bau mir ein Kit"
  sagt, ist das ein eigenes Projekt mit `kit_export` am Ende —
  nicht eine Eddie-Aktion.
