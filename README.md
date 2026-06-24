# 𝑣 vance

**Collaborative Brain.** Vance is a system that takes assignments, executes them with the right tools and engines, and delivers **verifiable results** — for teams, not just individuals.

Vance is more than a chatbot or coding assistant. It is a server on which assignments run for hours and days, every step stays visible, and every result is traceable back to its source.

> [!WARNING]
> **Beta.** Vance is in active development. APIs, data model, configuration keys and engine behaviour can change between releases. Suitable for hands-on experimentation and early adopters; not yet hardened for unattended production use.

> 🇩🇪 German version: [`README-DE.md`](README-DE.md)

## What Vance actually does

- **Assignment in, result out.** An assignment is more than a question: it has context, tools, and a goal. Vance decides context-dependently which engine, which recipe and which tools handle the job — and works it through, not in the RAM of a session, but as a persistent Think-Process in MongoDB.
- **Verifiable results, not plausible ones.** Every engine step, every tool call and every document write is visible and traceable. Documents are versioned (`document_archives`), tool calls carry source blocks, inbox items hold replies and delegations. Result = output + traceable path to it.
- **The right engine for the job.** Engines are Java algorithms with a lifecycle — code drives the flow, not the LLM. `vogon` runs strict phase pipelines with gates, `marvin` grows dynamic task trees, `lunkwill` runs as a focused worker with defined stop paths, `trillian` coordinates agentic user loops cross-project, `arthur`/`eddie` hold the user session, `slartibartfast`/`hactar` generate and execute scripts. Which engine? The recipe decides.
- **Recipes instead of code changes.** Recipe = YAML configuration: engine + default params + prompt prefix + tool adjustments. Few engines (structural algorithms), many recipes (named configuration bundles). To add a new type of assignment, you write a recipe — no Java.
- **Project Kits & Cascade.** Recipes, prompts, tools, settings flow through a cascade: bundled defaults → tenant → project. Project Kits are Git bundles that make a project productive instantly (`kernel-security`, `python-data-science`, your own). Teams maintain their kits centrally, projects pull in what they need.
- **Scopes from day one.** Tenant → project group → project → session → think-process. Memory cascades downward and is isolated laterally. Permissions, quotas and settings hang off the scope — the foundation for multi-user operation.
- **Live working environment with documents and notes.** Cortex unites chat, document and execute in one surface. Documents come in many kinds (Markdown, mindmap, sheet, kanban, slides, graph, diagram, checklist, …) and are shared live — presence, 3-way merge, versioning included. Notes attached to a document are part of the assignment, not external bookkeeping.
- **Several clients, one Brain.** CLI (`vance-foot`), Web UI (`vance-face`), Mobile (`vance-facelift` — Capacitor wrapper around the deployed Web UI, one native WKWebView per account for full cookie isolation). The Brain is the single source of truth; clients are different entry points — not views on the same thing.

## For teams, not just individuals

Vance is designed as a multi-user system: tenants, project sharing, service accounts, quotas and permissions live in the data model. That already covers today:

- **Shared projects** with centrally maintained kits, recipes and prompt manuals
- **Service accounts** (`_trillian-0…`, `_daemon-…`) for headless worker loops that run on someone's behalf
- **Inbox system** with delegation, tags, criticality and reply lifecycle for asynchronous coordination
- **Live-WS channels** (`session`, `documents`) with presence and cross-pod routing via Redis Pub/Sub

The full multi-user UI layer (team management, role UI, shared sessions) is in progress — the foundation is in place.

## What Vance is not

Not a team-chat replacement (Slack/Teams), not a project-management tool (Jira/Linear), not a generic publishing workflow. Finished artefacts that live on outside Vance — a shared spec, a final issue, shipped code — go out via export to Google Docs, Jira, Obsidian, your IDE. Vance is the workplace and the brain, not the filing cabinet for the end product.

## Concepts in one line each

| Term | Meaning |
|---|---|
| **Assignment** | What the user (or another process) wants done. Executed by recipe + engine. |
| **Engine** | Java algorithm with a lifecycle (details below in "Engines at a glance"). |
| **Recipe** | YAML config: engine + default params + prompt prefix + tool adjustments. Many of them, no code change required. |
| **Think Process** | Running assignment instance, persisted in Mongo. Status, task tree, inbox, history. |
| **Project Kit** | Git repo with skills/recipes/tools/settings, imported into a project. |
| **Scope** | Tenant/group/project/session/process — visibility for memory and permissions. |

## Engines at a glance

| Engine | Role | What it does |
|---|---|---|
| `arthur` | Reactive Session | Reactive user-chat engine: receives inbox events, calls LLM + tools, replies in chat. Reference implementation for the engine framework. |
| `eddie` | Default Session | Standard user-session engine. Coordinates user input and delegates assignments to worker engines. |
| `ford` | Single-LLM Worker | One turn = one LLM call with tool loop. Fast generalist, default worker for the orchestrator engines. |
| `marvin` | Deep-Think | Vertical decomposition with a dynamically growing task tree in Mongo (PLAN/WORKER/USER_INPUT/AGGREGATE, pre-order DFS). For deep, tree-shaped work. |
| `vogon` | Strategy Runner | Deterministic phase pipeline with gates, checkpoints, loops, forks and escalation. For structured workflows with hard handovers. |
| `zaphod` | Multi-Head | Horizontal multi-perspective: several heads work the same question in parallel, Zaphod synthesises. For view comparison and cross-validation. |
| `jeltz` | Schema Loop | Single-shot with JSON-schema validation: question in, schema-validated JSON out. Retries on schema violations, structured error after that. |
| `lunkwill` | Focused Worker | Endless-by-design worker with four defined stop paths (natural / `_terminate` / external / safety-net). First production recipe: `coding`. |
| `trillian` | Agentic Loop | Agentic user loop with a service account: two sessions (human control + headless `_trillian-…` user loop), cross-project spawn capable. For autonomous, persistent worker loops. |
| `slartibartfast` | Authoring | Meta-engine that generates or updates recipes (YAML) and scripts (SCRIPT_JS). Typically hands off to Hactar for execution. |
| `hactar` | Script Execution | Pure script executor: loads SCRIPT_JS, validates minimally, runs. Authoring lives in Slartibartfast; Hactar is just runtime. |
| `agrajag` | Tool Health | Service engine for tool-failure classification via LLM. Diagnoses why a tool call failed. |
| `magrathea` | Workflow Runtime | Not a think engine — its own lifecycle class: runs YAML workflows (phases, steps, sub-process spawns). Composable with engine calls. |
| `fook` | Triage Service | Bug and feature triage: reporters (LLM, web menu, Foot `/support`) submit free-text, Fook decides via a LightLLM call between `new_ticket`/`merge_into`/`discard` and stores tickets in the `_vance` tenant. Optional upstream transfer to GitHub Issues. |
| `fenchurch` | Image Service | Vance's only image generator: service + tool set (`image_generate`, `image_style_*`), synchronous provider call, concatenative style cascade across tenant → user → project → session. Aliases `default:image` / `default:image-high`. |
| `zarniwoop` | Research Service | Unified search/research layer with pluggable protocols (web, Wikipedia, OpenAlex, arXiv, OpenLibrary, …). One endpoint = one instance with its own quotas and scopes. In migration: existing search tools still run directly on Serper. |
| `ursa` | Trigger System | Not an engine but the trigger subsystem with three paths: **Scheduler** (time-based), **Ursahooks** (internal lifecycle events) and **Events** (external HTTP calls). All three fire the same `TriggerAction` hierarchy (recipe / script / workflow). |

## Tech stack

Java 25 + Spring Boot 4 + MongoDB + langchain4j/langgraph4j (Brain) · TypeScript + Vue 3 + Vite (Web) · Capacitor + WKWebView (Mobile, iOS via `vance-facelift`) · Picocli + JLine 3 + Lanterna (CLI).

## Status

In active development. Brain, CLI and Web UI run locally; twelve think-engines are implemented (`arthur`, `eddie`, `ford`, `marvin`, `vogon`, `zaphod`, `jeltz`, `lunkwill`, `trillian`, `slartibartfast`, `hactar`, `agrajag`) plus the workflow runtime `magrathea` and the services `fook`, `fenchurch`, `zarniwoop`. Tenants, service accounts and permissions are active in the data model; the team UI is landing incrementally.

## Documentation

Full docs at <https://vance.mhus.de>. To run Vance locally with Docker, see <https://github.com/mhus/vance-startup>.

## License

**Business Source License 1.1** — see [`LICENSE.txt`](LICENSE.txt). Source-available, not classic open source. On **2029-06-23** (three years after initial release) the license automatically converts to **AGPLv3**.

**Permitted under the Additional Use Grant** (including production):

- Personal use, educational use, research
- Internal business purposes (your own company, your own employees)
- Consulting & professional services
- Customer-specific deployments (one instance per customer)

**Not permitted**: offering Vance as a hosted, managed or SaaS service to third parties when Vance forms a substantial part of the service.

**Dual commercial license**: anyone working outside this grant (e.g. multi-tenant Vance hosting) can purchase a commercial license from the licensor. Contact: see LICENSE.txt.

**Enterprise features** (SSO, audit, team management) live separately under their own commercial license: [`vance-ee`](https://github.com/mhus/vance-ee).
