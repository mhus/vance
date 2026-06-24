# 𝑣 vance

**Collaborative Brain.** Vance ist ein System, das Aufträge annimmt, sie mit den richtigen Werkzeugen und Engines umsetzt, und **belegbare Ergebnisse** liefert — für Teams, nicht nur für Einzelpersonen.

Vance ist mehr als ein Chatbot oder Code-Assistent. Es ist ein Server, auf dem Aufträge über Stunden und Tage laufen, jeder Schritt sichtbar bleibt und jedes Ergebnis bis zu seiner Quelle rückverfolgbar ist.

> [!WARNING]
> **Beta.** Vance ist in aktiver Entwicklung. APIs, Datenmodell, Konfigurations-Keys und Engine-Verhalten können sich zwischen Releases ändern. Geeignet für Hands-on-Experimente und Early Adopters; noch nicht gehärtet für unbeaufsichtigten Produktiv-Einsatz.

> 🇬🇧 English version: [`README.md`](README.md)

## Was Vance konkret macht

- **Auftrag rein, Ergebnis raus.** Ein Auftrag ist mehr als eine Frage: er hat Kontext, Werkzeuge, ein Ziel. Vance entscheidet kontextabhängig, welche Engine, welches Recipe und welche Tools den Job erledigen — und arbeitet ihn ab, nicht im RAM einer Session, sondern als persistenten Think-Process in MongoDB.
- **Belegbare Ergebnisse, nicht plausible.** Jeder Engine-Schritt, jeder Tool-Call und jeder Document-Write ist sichtbar und nachvollziehbar. Documents sind versioniert (`document_archives`), Tool-Calls führen Source-Blocks mit, Inbox-Items halten Antworten und Delegationen fest. Ergebnis = Output + nachverfolgbarer Weg dorthin.
- **Die richtige Engine für den Auftrag.** Engines sind Java-Algorithmen mit Lifecycle — nicht der LLM steuert den Ablauf, sondern Code. `vogon` fährt strikte Phasen-Pipelines mit Gates, `marvin` baut dynamische Task-Trees, `lunkwill` läuft als Focused-Worker mit definierten Stop-Pfaden, `trillian` koordiniert agentic User-Loops cross-project, `arthur`/`eddie` halten die User-Session, `slartibartfast`/`hactar` generieren und führen Scripts aus. Welche Engine? Das entscheidet das Recipe.
- **Recipes statt Code-Änderungen.** Recipe = YAML-Konfiguration: Engine + Default-Params + Prompt-Prefix + Tool-Anpassungen. Wenige Engines (strukturelle Algorithmen), viele Recipes (benannte Konfigurationsbündel). Wer einen neuen Auftragstyp will, schreibt ein Recipe — kein Java.
- **Project Kits & Cascade.** Recipes, Prompts, Tools, Settings kommen per Cascade: Bundled-Defaults → Tenant → Projekt. Project Kits sind Git-Bundles, die ein Projekt sofort produktiv machen (`kernel-security`, `python-data-science`, eigene). Teams pflegen ihre Kits zentral, Projekte ziehen sich was sie brauchen.
- **Scopes von Anfang an.** Tenant → Projekt-Gruppe → Projekt → Session → Think-Process. Memory kaskadiert nach unten, ist seitlich isoliert. Rechte, Quotas und Settings hängen am Scope — Grundlage für Mehrbenutzerbetrieb.
- **Live-Arbeitsumfeld mit Documents und Notizen.** Cortex vereint Chat, Document und Execute in einer Oberfläche. Documents kommen in vielen Kinds (Markdown, Mindmap, Sheet, Kanban, Slides, Graph, Diagram, Checklist, …) und werden live geteilt — Presence, 3-way-Merge, Versionierung inklusive. Notizen am Document sind Teil des Auftrags, nicht externe Verwaltung.
- **Mehrere Clients, ein Brain.** CLI (`vance-foot`), Web-UI (`vance-face`), Mobile (`vance-facelift` — Capacitor-Wrapper um die deployte Web-UI, ein nativer WKWebView pro Account für vollständige Cookie-Isolation). Brain ist Single Source of Truth, Clients sind unterschiedliche Zugänge — keine Views auf dasselbe.

## Für Teams, nicht nur Einzelpersonen

Vance ist als Mehrbenutzer-System konzipiert: Tenants, Project-Sharing, Service-Accounts, Quotas und Rechte sitzen im Datenmodell. Das schließt heute schon ein:

- **Geteilte Projekte** mit zentral gepflegten Kits, Recipes und Prompt-Manuals
- **Service-Accounts** (`_trillian-0…`, `_daemon-…`) für headless Worker-Loops, die im Auftrag laufen
- **Inbox-System** mit Delegation, Tags, Criticality und Antwort-Lifecycle für asynchrone Koordination
- **Live-WS-Kanäle** (`session`, `documents`) mit Presence und Cross-Pod-Routing über Redis Pub/Sub

Die volle Multi-User-UI-Schicht (Team-Verwaltung, Rollen-UI, geteilte Sessions) ist in Arbeit — das Fundament steht.

## Was Vance nicht ist

Kein Team-Chat-Ersatz (Slack/Teams), kein Projektmanagement-Tool (Jira/Linear), kein generischer Publishing-Workflow. Fertige Artefakte, die außerhalb von Vance weiterleben sollen — geteilte Spec, finales Issue, ausgelieferter Code — gehen per Export nach Google Docs, Jira, Obsidian, IDE. Vance ist Arbeitsplatz und Brain, nicht die Ablage für das Endprodukt.

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

| Engine | Rolle | Was sie tut |
|---|---|---|
| `arthur` | Reactive Session | Reaktive User-Chat-Engine: nimmt Inbox-Events entgegen, ruft LLM + Tools, antwortet im Chat. Referenz-Implementierung für das Engine-Framework. |
| `eddie` | Default Session | Standard-User-Session-Engine. Koordiniert User-Eingaben und delegiert Aufträge an Worker-Engines. |
| `ford` | Single-LLM Worker | Ein Turn = ein LLM-Call mit Tool-Loop. Schneller Generalist, Default-Worker für die Orchestrator-Engines. |
| `marvin` | Deep-Think | Vertikale Dekomposition mit dynamisch wachsendem Task-Tree in Mongo (PLAN/WORKER/USER_INPUT/AGGREGATE, Pre-Order DFS). Für tiefe, baumartige Bearbeitung. |
| `vogon` | Strategy-Runner | Deterministische Phasen-Pipeline mit Gates, Checkpoints, Loops, Forks und Escalation. Für strukturierte Workflows mit harten Übergaben. |
| `zaphod` | Multi-Head | Horizontale Multi-Perspektive: mehrere Köpfe arbeiten parallel an derselben Frage, Zaphod synthetisiert. Für Sichten-Vergleich und Quervalidierung. |
| `jeltz` | Schema-Loop | Single-Shot mit JSON-Schema-Validation: Frage rein, schema-validiertes JSON raus. Retries bei Schema-Verstößen, danach strukturierter Fehler. |
| `lunkwill` | Focused Worker | Endless-by-design Worker mit vier definierten Stop-Pfaden (natural / `_terminate` / external / safety-net). Erstes produktives Recipe: `coding`. |
| `trillian` | Agentic Loop | Agentic User-Loop mit Service-Account: zwei Sessions (Mensch-Control + headless `_trillian-…`-User-Loop), cross-project-spawn-fähig. Für autonome, persistente Worker-Loops. |
| `slartibartfast` | Authoring | Meta-Engine, die Recipes (YAML) und Scripts (SCRIPT_JS) generiert oder updated. Übergibt typischerweise an Hactar zur Ausführung. |
| `hactar` | Script-Execution | Pure Script-Executor: lädt SCRIPT_JS, validiert minimal, führt aus. Authoring lebt in Slartibartfast, Hactar ist nur Runtime. |
| `agrajag` | Tool-Health | Service-Engine zur Tool-Fehler-Klassifikation per LLM. Diagnostiziert, warum ein Tool-Call gescheitert ist. |
| `magrathea` | Workflow-Runtime | Keine Think-Engine, sondern eigene Lifecycle-Klasse: führt YAML-Workflows aus (Phasen, Schritte, Sub-Process-Spawns). Mischbar mit Engine-Aufrufen. |
| `fook` | Triage-Service | Bug- und Feature-Triage: Reporter (LLM, Web-Menü, Foot `/support`) schicken Freitext, Fook entscheidet per LightLLM-Call `new_ticket`/`merge_into`/`discard` und legt Tickets im `_vance`-Tenant ab. Optionaler Upstream-Transfer zu GitHub Issues. |
| `fenchurch` | Bild-Service | Vance's einziger Bildgenerator: Service + Tool-Set (`image_generate`, `image_style_*`), synchroner Provider-Call, konkatenative Style-Cascade über Tenant→User→Projekt→Session. Aliase `default:image` / `default:image-high`. |
| `zarniwoop` | Research-Service | Einheitlicher Such-/Recherche-Layer mit pluggable Protokollen (Web, Wikipedia, OpenAlex, arxiv, OpenLibrary, …). Ein Endpoint = eine Instanz mit eigenen Quotas und Scopes. In Migration: bestehende Search-Tools laufen noch direkt auf Serper. |
| `ursa` | Trigger-System | Keine Engine, sondern das Auslöser-Subsystem mit drei Pfaden: **Scheduler** (zeitbasiert), **Ursahooks** (interne Lifecycle-Events) und **Events** (externe HTTP-Calls). Alle drei feuern dieselbe `TriggerAction`-Hierarchie (Recipe / Script / Workflow). |

## Tech-Stack

Java 25 + Spring Boot 4 + MongoDB + langchain4j/langgraph4j (Brain) · TypeScript + Vue 3 + Vite (Web) · Capacitor + WKWebView (Mobile, iOS via `vance-facelift`) · Picocli + JLine 3 + Lanterna (CLI).

## Status

In aktiver Entwicklung. Brain, CLI und Web-UI laufen lokal; zwölf Think-Engines sind implementiert (`arthur`, `eddie`, `ford`, `marvin`, `vogon`, `zaphod`, `jeltz`, `lunkwill`, `trillian`, `slartibartfast`, `hactar`, `agrajag`) plus Workflow-Runtime `magrathea` und die Dienste `fook`, `fenchurch`, `zarniwoop`. Tenants, Service-Accounts und Rechte sind im Datenmodell aktiv, die Team-UI kommt schrittweise.

## Lizenz

**Business Source License 1.1** — siehe [`LICENSE.txt`](LICENSE.txt). Source-available, kein klassisches Open-Source. Am **2029-06-23** (drei Jahre ab Initial-Release) konvertiert die Lizenz automatisch zu **AGPLv3**.

**Erlaubt im Rahmen des Additional Use Grant** (auch produktiv):

- Personal use, educational use, research
- Internal business purposes (eigene Firma, eigene Mitarbeiter)
- Consulting & professional services
- Customer-specific deployments (eine Instanz pro Kunde)

**Nicht erlaubt**: Vance als gehosteter, gemanagter oder SaaS-Service an Dritte anbieten, wenn Vance einen substantiellen Teil des Services ausmacht.

**Dual Commercial License**: Wer außerhalb dieser Grant arbeiten will (z.B. Multi-Tenant-Vance-Hosting), kann eine kommerzielle Lizenz vom Lizenzgeber erwerben. Kontakt: siehe LICENSE.txt.

**Enterprise-Features** (SSO, Audit, Team-Management) liegen separat unter eigener kommerzieller Lizenz: [`vance-ee`](https://github.com/mhus/vance-ee).
