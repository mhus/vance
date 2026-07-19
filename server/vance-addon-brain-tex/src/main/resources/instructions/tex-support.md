# LaTeX Support

Vance kompiliert LaTeX-Dokumente (`.tex`) zu PDF **als Damogran-Compose-Task**
(`type: tex-task`). Es gibt kein eigenes `tex2pdf`-Tool und kein `tex-compose`-
Kind mehr — LaTeX ist ein Task im Workspace-Compose (siehe das Manual
`damogran-compose` und `specification/public/damogran-system.md`).

## Ablauf

Ein Compose importiert die `.tex`-Quelle (+ `.bib`/`.sty`/Bilder) in den
Workspace, führt den `tex-task` aus (kompiliert `main` mit `latexmk`) und
exportiert das PDF:

```yaml
workspace:
  name: thesis
  type: temp
import:
  - from: vance:thesis.tex
    to: thesis.tex
  - from: vance:references.bib
    to: references.bib
  - from: vance:images/figure1.png
    to: images/figure1.png
tasks:
  - type: tex-task
    main: thesis.tex          # Entry-Point (required)
    engine: pdflatex          # pdflatex | xelatex | lualatex (default: pdflatex)
    output: thesis.pdf        # PDF-Ziel im Workspace (default: <main-basename>.pdf)
export:
  - from: thesis.pdf
    to: vance:thesis.pdf
```

Der `tex-task` kompiliert den **bereits gefüllten** Workspace — Datei-Transport
macht der `import`-Block, nicht der Task. Alle vom Build benötigten Dateien
müssen also importiert sein (`.tex`, `.bib`, `.sty`, Bilder).

## Executor

`TexService.resolveExecutor` wählt die Compile-Strategie über die Einstellung
`tex.executor` (Cascade Project → `_tenant`, Fallback Property
`vance.tex.executor`, Default `local`):

- **`local`** (`Tex2PdfLocalExecutor`) — `latexmk` via `ProcessBuilder` auf dem
  Brain-Host (Dev/CI mit TeX Live / MacTeX).
- **`rbehzadan`** (`Tex2PdfRbehzadanExecutor`) — zippt den Workspace und postet
  an einen externen `rbehzadan/tex2pdf`-Docker-Service. Config über das
  Setting-Form `tex-setup`.

## TeX Live (local Executor)

Im Brain-Docker-Image installiert:

```dockerfile
RUN apt-get update && apt-get install -y --no-install-recommends \
    texlive-latex-base texlive-latex-extra \
    texlive-fonts-recommended texlive-fonts-extra latexmk \
    && rm -rf /var/lib/apt/lists/*
```

Kein `tlmgr install` zur Runtime (Sicherheitsrisiko) — alles Benötigte muss im
Image (bzw. beim rbehzadan-Service) vorhanden sein.
