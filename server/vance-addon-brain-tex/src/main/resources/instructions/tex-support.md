# LaTeX Support

Vance kann LaTeX-Dokumente (`.tex`) zu PDF kompilieren. Das Feature nutzt
das `tex-compose` Document-Kind als Build-Manifest und das `tex2pdf`-Tool
für die Kompilierung.

## Überblick

1. **Document-Kind `tex-compose`** — ein YAML-Manifest, das alle Dateien
   für einen LaTeX-Build deklariert (analog zu `docker-compose.yml`)
2. **`tex2pdf`-Tool** — liest das Manifest, transportiert alle Dateien in
   einen temp Workspace, führt `latexmk` aus, importiert das fertige PDF
   als Document
3. **TeX Live** — im Docker-Image installiert (`texlive-latex-base`,
   `texlive-latex-extra`, `latexmk`)

## tex-compose.yaml

Ein `tex-compose`-Document ist ein YAML-Manifest mit folgendem Aufbau:

```yaml
# Minimal
main: thesis.tex
files:
  - thesis.tex
  - references.bib
  - images/figure1.png
```

```yaml
# Vollständig
main: thesis.tex
engine: pdflatex      # pdflatex | xelatex | lualatex (default: pdflatex)
passes: auto           # auto = latexmk (default)
output: thesis.pdf     # default: <main-basename>.pdf
files:
  - thesis.tex
  - references.bib
  - custom.sty
  - images/figure1.png
  - images/figure2.png
```

### Felder

| Feld | Pflicht | Default | Beschreibung |
|---|---|---|---|
| `main` | ja | — | Entry-Point `.tex`-Datei |
| `files` | ja | — | Liste aller Dateien, die für den Build benötigt werden (inkl. `main`) |
| `engine` | nein | `pdflatex` | TeX-Engine: `pdflatex`, `xelatex`, `lualatex` |
| `passes` | nein | `auto` | `auto` = latexmk übernimmt Mehrfach-Pass-Logik |
| `output` | nein | `<main>.pdf` | Name des Output-PDFs |

### Wichtig

- **`files` muss alle Dateien enthalten**, die der Build braucht — `.tex`,
  `.bib`, `.sty`, Bilder. Nur deklarierte Dateien werden transportiert.
- **Bilder werden binär transportiert** — das Tool nutzt
  `DocumentService.loadContent` (InputStream), funktioniert für alle
  Dateitypen.
- **Kein `packages`-Feld** — `tlmgr install` zur Runtime aus User-Daten
  ist ein Sicherheitsrisiko. Alles was gebraucht wird muss im Docker-Image
  sein.

## Tool: `tex2pdf`

```
tex2pdf(composePath: string) → {
  success: boolean,
  pdfPath?: string,      // document path des erzeugten PDFs
  markdownLink?: string,  // vance:/ link für Chat-Einbettung
  error?: string,        // Fehlermeldung bei Misserfolg
  logExcerpt?: string,   // LaTeX-Log-Auszug bei Misserfolg
  elapsedMs: number
}
```

### Ablauf

1. Manifest lesen und parsen
2. Temp Workspace RootDir anlegen (UUID-dirName, isoliert)
3. Alle deklarierten Dateien aus Document-Storage → Workspace transportieren
4. `latexmk -pdf -interaction=nonstopmode -halt-on-error -timeout=120 <main>`
5. Bei Erfolg: PDF als `application/pdf` Document importieren
6. Workspace aufräumen (immer, auch bei Fehlern)

### Timeout

- 120s latexmk-eigenes Timeout (`-timeout=120`)
- 150s harter Process-Timeout (`Process.waitFor`)

### Concurrency

Jeder Build bekommt einen eigenen UUID-named RootDir. Parallele Builds
sind vollständig isoliert.

## Docker-Image

TeX Live wird im Brain-Docker-Image installiert:

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends \
    texlive-latex-base \
    texlive-latex-extra \
    texlive-fonts-recommended \
    texlive-fonts-extra \
    latexmk \
    && rm -rf /var/lib/apt/lists/*
```

~1-1.5 GB zusätzlich. Für die Addon-Phase wird ein separates
TeX-Docker-Image angestrebt.

## Beispiel

1. `thesis.tex` als Document anlegen (`kind: text`)
2. `references.bib` als Document anlegen
3. `images/figure1.png` als Document anlegen (binary upload)
4. `tex-compose.yaml` als Document anlegen (`kind: tex-compose`):

```yaml
main: thesis.tex
files:
  - thesis.tex
  - references.bib
  - images/figure1.png
```

5. Tool aufrufen: `tex2pdf(composePath: "thesis/tex-compose.yaml")`
6. Ergebnis: PDF unter `thesis/thesis.pdf` als Document
