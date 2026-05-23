# 𝑣 Vance

**Plattform für hybrides KI-Denken.** Deterministische Strukturen führen, KI-Agenten arbeiten darin. Persistente Projekte, Memory in MongoDB, Tenants und Rechte.

Vance ist kein Chatbot und kein Coding-Assistent. Es ist ein Server, auf dem KI-Denkprozesse über Tage laufen, persistent bleiben und sich vom Nutzer steuern lassen.

## Was Vance konkret macht

- **Persistente Denkprozesse.** Ein Think-Process hat Status, Lifecycle, Memory und überlebt Disconnects. Mongo speichert Verlauf und Zwischenergebnisse, nicht der RAM einer Session.
- **Determinismus + KI gemischt.** Engines steuern den Ablauf in Java-Code, nicht der LLM. `vogon` fährt strikte Phasen-Pipelines mit Gates und Output-Schemas, `marvin` baut Task-Trees dynamisch, `ford` arbeitet als Generalist-Worker, `arthur` und `eddie` koordinieren die User-Session. Der LLM-Call ist Werkzeug innerhalb der Engine-Logik.
- **Project Kits.** Git-Bundle aus Skills, Recipes, Tools und Settings. `kit install` macht ein Projekt sofort produktiv. Vorkonfigurierte Kits sind möglich (z.B. `kernel-security`, `python-data-science`); Tenants pflegen eigene.
- **Pro Projekt anpassbar.** Recipes, Prompts, Tools, Settings — alles kommt per Cascade aus Bundled-Defaults → Tenant → Projekt. Jedes Projekt überschreibt selektiv, ohne Code anzufassen.
- **Scopes von Anfang an.** Tenant → Projekt-Gruppe → Projekt → Session → Think-Process. Memory kaskadiert nach unten, ist seitlich isoliert. Rechte, Quotas und Settings hängen am Scope.
- **Mehrere Clients, ein Brain.** CLI (`vance-foot`), Web-UI (`vance-face`), Mobile (`vance-fingers`). Brain ist Single Source of Truth, Clients sind unterschiedliche Zugänge — keine Views auf dasselbe.

## Was Vance nicht ist

Kein Dokument-Editor, kein Projektmanagement-Tool, kein Notebook, kein Slack-Ersatz. Ergebnisse exportiert Vance nach Google Docs, Jira, Obsidian etc. — die Verwaltung dieser Artefakte passiert dort, nicht in Vance.

## Begriffe in einer Zeile

| Begriff | Bedeutung |
|---|---|
| **Engine** | Java-Algorithmus mit Lifecycle. Session-Layer (`arthur`, `eddie`), Worker (`ford`, `marvin`, `vogon`, `zaphod`, `jeltz`), Meta-Engines, die Recipes generieren (`slartibartfast`, `hactar`), Service-Engine für Tool-Health-Diagnose (`agrajag`, klassifiziert Tool-Fehler per LLM), plus Workflow-Runtime `magrathea`. |
| **Recipe** | YAML-Konfig: Engine + Default-Params + Prompt-Prefix + Tool-Anpassungen. Viele, kein Code-Change. |
| **Think Process** | Laufende Instanz, persistiert in Mongo. Status, Task-Tree, Inbox. |
| **Project Kit** | Git-Repo mit Skills/Recipes/Tools/Settings, das in ein Projekt importiert wird. |
| **Scope** | Tenant/Gruppe/Projekt/Session/Process — Sichtbarkeit für Memory und Rechte. |

## Tech-Stack

Java 25 + Spring Boot 4 + MongoDB + langchain4j/langgraph4j (Brain) · TypeScript + Vue 3 + Vite (Web) · React Native + Expo (Mobile) · Picocli + JLine 3 + Lanterna (CLI).

## Status

In aktiver Entwicklung. Brain, CLI und Web-UI laufen lokal; elf Engines sind implementiert: `arthur`, `eddie`, `ford`, `marvin`, `vogon`, `zaphod`, `jeltz`, `slartibartfast`, `hactar`, `magrathea`, `agrajag`. Tenants und Rechte sind im Datenmodell vorbereitet, die volle Multi-User-Schicht kommt später.

## Lizenz

Vance Non-Commercial, Non-Production Copyleft v1.0 — siehe [`LICENSE.txt`](LICENSE.txt). Nutzung nur zu nicht-kommerziellen, nicht-produktiven Zwecken (Testing, Evaluation, Research). Enterprise-Features (SSO, Audit, Team-Management) separat unter kommerzieller Lizenz: [`vance-ee`](https://github.com/mhus/vance-ee).
