# 𝑣 vancetope

**[Website](https://www.vancetope.com)** · **[Erste Schritte](https://www.vancetope.com/getting-started)** · **[Blog](https://www.vancetope.com/blog/)**

**Ein persönliches Projekt.** Vancetope habe ich gebaut, um LLM-Agenten zu entwickeln — und sie so zu formen, dass ich damit wirklich produktiv arbeiten kann. Über die Zeit ist alles reingewandert, was ich spannend fand, plus ein paar Dinge, von denen ich glaube, dass andere was davon haben.

Es ist ein Projekt, kein Produkt — kein Support-Vertrag, keine Roadmap-Versprechen. Der Quellcode ist öffentlich: lesen, ausprobieren, laufen lassen. Er ist Open Source unter der GNU AGPLv3.

> [!WARNING]
> **Beta.** Vancetope ist in aktiver Entwicklung. APIs, Datenmodell, Konfigurations-Keys und Engine-Verhalten können sich zwischen Releases ändern. Gut für Hands-on-Experimente; nicht gehärtet für unbeaufsichtigten Produktiv-Einsatz.

> 🇬🇧 English version: [`README.md`](README.md)

## Die Idee

Vancetope ist ein Server (das "Brain"), auf dem Agenten Aufträge über Stunden und Tage bearbeiten — und den man fast vollständig aus sich selbst heraus gestalten kann, weil Konfiguration, Verhalten und Wissen alle als Dokumente in der Datenbank liegen.

## Von Agenten geschrieben. Mit Absicht.

Jede Zeile von Vancetope ist AI-geschrieben — dirigiert, reviewt und geformt von einem Menschen. Das ist kein Geständnis, sondern der ganze Punkt: Vancetope ist das, was dabei herauskommt, wenn man über Monate *mit* Agenten arbeitet statt nur mit ihnen zu chatten — ein großes, kohärentes System, das man laufen lassen, lesen und ändern kann. Tool und Beweis sind dasselbe.

## Was es ausmacht

- **Alles ist ein Dokument.** Templates, Recipes, Prompts, Scheduler, Hooks, Settings, Manuals — alle als Dokument in MongoDB abgelegt. Ein neues Recipe ist ein neues Dokument, ein neuer Automatismus ein neues Hook-Dokument. Das System konfiguriert sich aus seinen eigenen Daten — und Agenten können dasselbe tun.
- **Agenten steuern (fast) alles.** Nahezu jede Fähigkeit ist als Tool verfügbar: Dokumente schreiben, Recipes anlegen, Prozesse spawnen, Trigger setzen, recherchieren, an andere Agenten delegieren. Du gibst die Richtung vor und greifst ein, wenn du willst — der Ablauf bleibt sichtbar und steuerbar.
- **Projekte grenzen ab.** Ein Projekt ist der abgegrenzte Bereich mit eigenen Dokumenten, eigener Konfiguration und eigenen Agenten, sodass verschiedene Setups nebeneinander leben, ohne sich zu stören. Memory, Rechte und Settings hängen an einer Scope-Kaskade (Tenant → Projekt → Session → Think-Process) und vererben sich nach unten.
- **Ein Ort zum Arbeiten.** Cortex vereint Chat, Dokument und Ausführung auf einer Fläche. Dokumente kommen in vielen Kinds (Markdown, Workpage, Mindmap, Sheet, Kanban, Slides, Graph, Diagramm, Canvas, Checklist, …), und Apps bündeln sie zu etwas Ganzem (Workbook, Wiki, GTD, Kanban, Journal, Calendar, Canvasbook). Alles live geteilt — Presence, 3-way-Merge, Versionierung inklusive.
- **Kollaborativ.** Mehrere Menschen arbeiten gleichzeitig im selben Projekt, inklusive live editierter Dokumente. Agenten sind Teilnehmer wie Menschen — dieselbe Session, dieselben Dokumente.
- **Auftrag rein, nachvollziehbares Ergebnis raus.** Arbeit läuft als persistenter Think-Process in MongoDB, nicht im RAM einer Session. Dokumente sind versioniert (`document_archives`), Tool-Calls führen Source-Blocks mit, Inbox-Items halten Antworten und Delegationen fest. Ergebnis = Output + der Weg dorthin.
- **Gedächtnis, das bleibt.** Arbeit verdichtet sich zu Summaries und bleibt abrufbar; scope-aware Recall (RAG) läuft die Kaskade entlang, sodass ein Prozess auf das zurückgreift, was Projekt und Tenant schon wissen — nicht nur auf den aktuellen Chat.
- **Es läuft von selbst.** Das Ursa-Trigger-Subsystem feuert Recipes, Scripts oder Workflows aus drei Pfaden — Scheduler (zeitbasiert), Lifecycle-Hooks und externe Webhooks. Agenten handeln auf Timer oder Ereignis, nicht nur im Chat-Turn.
- **Die richtige Engine für den Auftrag.** Engines sind Java-Algorithmen mit Lifecycle — nicht der LLM steuert den Ablauf, sondern Code. Zum Beispiel fährt `vogon` strikte Phasen-Pipelines mit Gates, `marvin` baut dynamische Task-Trees, `frankie` läuft als Focused-Worker mit definierten Stop-Pfaden, `trillian` koordiniert agentic User-Loops cross-project, `arthur`/`eddie` halten die User-Session, `slartibartfast`/`hactar` generieren und führen Scripts aus. Welche Engine? Das entscheidet das Recipe.
- **Recipes statt Code-Änderungen.** Ein Recipe ist YAML-Konfig: Engine + Default-Params + Prompt-Prefix + Tool-Anpassungen. Wenige Engines (strukturelle Algorithmen), viele Recipes (benannte Konfigurationsbündel). Für einen neuen Auftragstyp schreibt man ein Recipe — kein Java.
- **Project Kits & Cascade.** Recipes, Prompts, Tools und Settings kommen per Cascade: Bundled-Defaults → Tenant → Projekt. Project Kits sind Git-Bundles, die ein Projekt sofort produktiv machen; Teams pflegen ihre Kits zentral, Projekte ziehen sich was sie brauchen.
- **Mehrere Clients, ein Brain.** CLI (`vance-foot`) — direkt auf Terminal-Ebene arbeiten, aber immer im Projekt mit allem, was drin hinterlegt ist. Web-UI (`vance-face`). Mobile (`vance-facelift` — Capacitor-Wrapper um die deployte Web-UI, ein isolierter WebView pro Account). Das Brain ist Single Source of Truth; Clients sind unterschiedliche Zugänge, keine Views auf dasselbe.
- **Connectoren nach außen.** Mail, Jira, Google-Dienste, MCP-Tools. Inputs kommen rein; fertige Artefakte, die woanders weiterleben, gehen raus.

## Was Vancetope nicht ist

Kein Team-Chat-Ersatz (Slack/Teams), kein Projektmanagement-Tool (Jira/Linear), kein generischer Publishing-Workflow. Fertige Artefakte, die außerhalb von Vancetope weiterleben sollen — geteilte Spec, finales Issue, ausgelieferter Code — gehen per Export nach Google Docs, Jira, Obsidian, IDE. Vancetope ist Arbeitsplatz und Brain, nicht die Ablage für das Endprodukt.

## Begriffe in einer Zeile

| Begriff | Bedeutung |
|---|---|
| **Auftrag** | Was der Nutzer (oder ein anderer Process) erledigt haben will. Wird durch Recipe + Engine umgesetzt. |
| **Engine** | Java-Algorithmus mit Lifecycle (Details unten in „Engines im Überblick"). |
| **Recipe** | YAML-Konfig: Engine + Default-Params + Prompt-Prefix + Tool-Anpassungen. Viele, kein Code-Change. |
| **Think Process** | Laufende Auftragsinstanz, persistiert in Mongo. Status, Task-Tree, Inbox, Verlauf. |
| **Project Kit** | Git-Repo mit Skills/Recipes/Tools/Settings, das in ein Projekt importiert wird. |
| **Scope** | Tenant/Gruppe/Projekt/Session/Process — Sichtbarkeit für Memory und Rechte. |

## Engines im Überblick

Eine Auswahl — die vollständige, aktuelle Liste steht unter [vancetope.com/agents](https://www.vancetope.com/agents/#engines), die mitgelieferten Recipes unter [vancetope.com/recipes](https://www.vancetope.com/recipes/).

| Engine | Was sie tut |
|---|---|
| `arthur` / `eddie` | Halten die User-Session: chatten, Aufträge an Worker delegieren, Ergebnisse zusammenführen. |
| `ford` | Schneller Generalist-Worker — ein LLM-Call mit Tool-Loop pro Turn. |
| `frankie` | Fokussierter, endless-by-design Worker mit definierten Stop-Pfaden; fährt das `coding`-Recipe. |
| `marvin` | Deep-Think: ein Task-Tree, der während der Arbeit wächst. |
| `vogon` | Deterministische Phasen-Pipeline mit Gates, Checkpoints, Loops und Eskalation. |
| `trillian` | Persistenter Agenten-Loop mit eigenem Service-Account, projektübergreifend. |

Um die Engines herum liegen Dienste wie `zarniwoop` (Recherche), `fenchurch` (Bilder), `hotblack` (Audio), `fook` (Triage) und das Trigger-Subsystem `ursa` (Scheduler, Hooks, Webhooks).

## Tech-Stack

Java 25 + Spring Boot 4 + MongoDB + langchain4j/langgraph4j (Brain) · TypeScript + Vue 3 + Vite (Web) · Capacitor + WKWebView (Mobile, iOS via `vance-facelift`) · Picocli + JLine 3 + Lanterna (CLI).

## Status

In aktiver Entwicklung. Brain, CLI, Web-UI, Desktop- und Mobile-Apps laufen lokal oder selbst gehostet. Tenants, Service-Accounts und Rechte sind im Datenmodell aktiv; die kollaborativen Multi-User-Features kommen schrittweise.

## Dokumentation

Vollständige Doku unter <https://www.vancetope.com>. Lokal mit einem Befehl starten — `curl -fsSL https://www.vancetope.com/install.sh | bash` — siehe den [Getting-Started-Guide](https://www.vancetope.com/getting-started).

## CLI installieren

Der `vancetope`-Terminal-Client verbindet sich mit einem laufenden Brain. Drei Wege:

```bash
# Homebrew (macOS + Linux) — bringt eigenes OpenJDK 25 mit, kein System-Java nötig
brew install mhus/vancetope/vancetope
vancetope

# Self-contained Bundle — eingebettetes Java, keine Installation; passendes Plattform-Asset
#   vancetope-<version>-{macos-arm64,macos-x64,linux-x64,windows-x64}.{tar.gz,zip}
gh release download <tag> -R mhus/vance -p 'vancetope-*-linux-x64.tar.gz'

# Rohes Fat-JAR — braucht installiertes Java 25
gh release download <tag> -R mhus/vance -p 'vancetope-*.jar'
java -jar vancetope-<version>.jar chat
```

Die Assets hängen an jedem [GitHub-Release](https://github.com/mhus/vance/releases).

## Lizenz

**GNU Affero General Public License v3.0** (`AGPL-3.0-only`) — siehe [`LICENSE.txt`](LICENSE.txt).

Nutzen, produktiv betreiben, ändern, weitergeben. Wer Vancetope verändert und anderen über ein Netzwerk zur Nutzung anbietet, muss ihnen den Quellcode der veränderten Fassung unter derselben Lizenz anbieten (AGPL §13).

Das gilt ab Release **0.6.0**; Releases bis einschließlich 0.5.0 sind unter der Business Source License 1.1 erschienen.

Copyright (c) 2024–2026 Mike Hummel. Beiträge werden unter dem [`CLA`](CLA.md) angenommen.

Enterprise-orientierte Features (SSO, Audit, Team-Management) liegen separat in [`vance-ee`](https://github.com/mhus/vance-ee) unter eigener Lizenz.
