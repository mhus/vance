# 𝑣 vancetope

**[Website](https://www.vancetope.com)** · **[Get started](https://www.vancetope.com/getting-started)** · **[Blog](https://www.vancetope.com/blog/)**

**A personal project.** Vancetope is something I built to develop LLM agents — and to shape them until I could actually work productively with them. Over time everything I found interesting went in, plus a few things I think others might get something out of.

It's a project, not a product — no support contract, no roadmap promises. The source is public: read it, run it, take it for a spin. It's open source under the GNU AGPLv3.

> [!WARNING]
> **Beta.** Vancetope is in active development. APIs, data model, configuration keys and engine behaviour can change between releases. Good for hands-on experimentation; not hardened for unattended production use.

> 🇩🇪 German version: [`README-DE.md`](README-DE.md)

## The idea

Vancetope is a server (the "Brain") on which agents work assignments over hours and days — and which you can shape almost entirely from within itself, because configuration, behaviour and knowledge all live as documents in the database.

## Written by agents. On purpose.

Every line of Vancetope is AI-written — directed, reviewed and shaped by one human. That's not a confession, it's the whole point: Vancetope is what working *with* agents over months actually builds — a large, coherent system you can run, read and change. The tool and the demo are the same thing.

## What makes it tick

- **Everything is a document.** Templates, recipes, prompts, schedulers, hooks, settings, manuals — all stored as documents in MongoDB. A new recipe is a new document, a new automation a new hook document. The system configures itself out of its own data — and agents can do the same.
- **Agents drive (almost) everything.** Nearly every capability is exposed as a tool: write documents, create recipes, spawn processes, set triggers, research, delegate to other agents. You give the direction and step in when you want — the flow stays visible and steerable.
- **Projects draw the boundaries.** A project is a bounded area with its own documents, configuration and agents, so different setups live side by side without interfering. Memory, permissions and settings hang off a scope cascade (tenant → project → session → think-process) and inherit downward.
- **A place to actually work.** Cortex unites chat, document and execute on one surface. Documents come in many kinds (Markdown, workpage, mindmap, sheet, kanban, slides, graph, diagram, canvas, checklist, …), and apps bundle them into something whole (workbook, wiki, GTD, kanban, journal, calendar, canvasbook). All shared live — presence, 3-way merge, versioning included.
- **Collaborative.** Several people work in the same project at once, including live-edited documents. Agents are participants alongside humans — same session, same documents.
- **Assignment in, traceable result out.** Work runs as a persistent think-process in MongoDB, not in session RAM. Documents are versioned (`document_archives`), tool calls carry source blocks, inbox items hold replies and delegations. Result = output + the path that led to it.
- **Memory that carries over.** Work compacts into summaries and stays retrievable; scope-aware recall (RAG) walks the cascade, so a process draws on what its project and tenant already know — not just the current chat.
- **It runs itself.** The Ursa trigger subsystem fires recipes, scripts or workflows from three paths — schedulers (time-based), lifecycle hooks and external webhooks. Agents act on a timer or an event, not only in a chat turn.
- **The right engine for the job.** Engines are Java algorithms with a lifecycle — code drives the flow, not the LLM. For example, `vogon` runs strict phase pipelines with gates, `marvin` grows dynamic task trees, `frankie` runs as a focused worker with defined stop paths, `trillian` coordinates agentic user loops cross-project, `arthur`/`eddie` hold the user session, `slartibartfast`/`hactar` generate and run scripts. Which engine? The recipe decides.
- **Recipes instead of code changes.** A recipe is YAML config: engine + default params + prompt prefix + tool adjustments. Few engines (structural algorithms), many recipes (named configuration bundles). To add a new kind of assignment, you write a recipe — no Java.
- **Project Kits & cascade.** Recipes, prompts, tools and settings flow through a cascade: bundled defaults → tenant → project. Project Kits are Git bundles that make a project productive instantly; teams maintain their kits centrally, projects pull in what they need.
- **Several clients, one Brain.** CLI (`vance-foot`) — work right at the terminal, but always inside a project with everything stored in it. Web UI (`vance-face`). Mobile (`vance-facelift` — a Capacitor wrapper around the deployed Web UI, one isolated WebView per account). The Brain is the single source of truth; clients are different entry points, not views on the same thing.
- **Connectors to the outside.** Mail, Jira, Google services, MCP tools. Inputs come in; finished artefacts that live on elsewhere go out.

## What Vancetope is not

Not a team-chat replacement (Slack/Teams), not a project-management tool (Jira/Linear), not a generic publishing workflow. Finished artefacts that live on outside Vancetope — a shared spec, a final issue, shipped code — go out via export to Google Docs, Jira, Obsidian, your IDE. Vancetope is the workplace and the brain, not the filing cabinet for the end product.

## Concepts in one line each

| Term | Meaning |
|---|---|
| **Assignment** | What the user (or another process) wants done. Executed by recipe + engine. |
| **Engine** | Java algorithm with a lifecycle (see "Engines at a glance" below). |
| **Recipe** | YAML config: engine + default params + prompt prefix + tool adjustments. Many of them, no code change required. |
| **Think Process** | Running assignment instance, persisted in Mongo. Status, task tree, inbox, history. |
| **Project Kit** | Git repo with skills/recipes/tools/settings, imported into a project. |
| **Scope** | Tenant/group/project/session/process — visibility for memory and permissions. |

## Engines at a glance

A few examples — the full, current list is at [vancetope.com/agents](https://www.vancetope.com/agents/#engines), the bundled recipes at [vancetope.com/recipes](https://www.vancetope.com/recipes/).

| Engine | What it does |
|---|---|
| `arthur` / `eddie` | Hold the user session: chat, delegate assignments to workers, synthesise results. |
| `ford` | Fast generalist worker — one LLM call with tool loop per turn. |
| `frankie` | Focused, endless-by-design worker with defined stop paths; runs the `coding` recipe. |
| `marvin` | Deep-think: a task tree that grows while the work runs. |
| `vogon` | Deterministic phase pipeline with gates, checkpoints, loops and escalation. |
| `trillian` | Persistent agent loop with its own service account, cross-project. |

Around the engines sit services such as `zarniwoop` (research), `fenchurch` (images), `hotblack` (audio), `fook` (triage) and the trigger subsystem `ursa` (scheduler, hooks, webhooks).

## Tech stack

Java 25 + Spring Boot 4 + MongoDB + langchain4j/langgraph4j (Brain) · TypeScript + Vue 3 + Vite (Web) · Capacitor + WKWebView (Mobile, iOS via `vance-facelift`) · Picocli + JLine 3 + Lanterna (CLI).

## Status

In active development. Brain, CLI, Web UI, desktop and mobile apps run locally or self-hosted. Tenants, service accounts and permissions are active in the data model; collaborative multi-user features are landing incrementally.

## Documentation

Full docs at <https://www.vancetope.com>. Run it locally with one command — `curl -fsSL https://www.vancetope.com/install.sh | bash` — see the [getting-started guide](https://www.vancetope.com/getting-started).

## Install the CLI

The `vancetope` terminal client connects to a running Brain. Three ways to get it:

```bash
# Homebrew (macOS + Linux) — brings its own OpenJDK 25, no system Java needed
brew install mhus/vancetope/vancetope
vancetope

# Self-contained bundle — embedded Java, no install; pick your platform asset
#   vancetope-<version>-{macos-arm64,macos-x64,linux-x64,windows-x64}.{tar.gz,zip}
gh release download <tag> -R mhus/vance -p 'vancetope-*-linux-x64.tar.gz'

# Raw fat-jar — needs Java 25 installed
gh release download <tag> -R mhus/vance -p 'vancetope-*.jar'
java -jar vancetope-<version>.jar chat
```

Assets live on each [GitHub release](https://github.com/mhus/vance/releases).

## License

**GNU Affero General Public License v3.0** (`AGPL-3.0-only`) — see [`LICENSE.txt`](LICENSE.txt).

Use it, run it in production, change it, share it. If you modify Vancetope and let others use it over a network, you have to offer them the source of your modified version under the same licence (AGPL §13).

This applies from release **0.6.0** on; releases up to and including 0.5.0 were published under the Business Source License 1.1.

Copyright (c) 2024–2026 Mike Hummel. Contributions are accepted under the [`CLA`](CLA.md).

Enterprise-oriented features (SSO, audit, team management) live separately in [`vance-ee`](https://github.com/mhus/vance-ee) under their own license.
