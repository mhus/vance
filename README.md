# 𝑣 Vance

**Platform for hybrid AI reasoning.** Deterministic structures lead, AI agents work inside them. Persistent projects, memory in MongoDB, tenants and permissions.

Vance is more than a chatbot or code assistant. It is a server on which AI reasoning processes run over days, stay persistent, and remain steerable by the user.

## What Vance actually does

- **A coworker, not an advisor.** Vance works on tasks across multiple steps — researching, analyzing, writing, calling tools. The engine decides what makes sense next, but inside structures you defined. Every step is visible and steerable.
- **Persistent thinking processes.** A Think Process has status, lifecycle, memory, and survives disconnects. Mongo stores history and intermediate results — not the RAM of a session.
- **Determinism + AI, mixed.** Engines drive the control flow in Java code, not the LLM. `vogon` runs strict phase pipelines with gates and output schemas, `marvin` grows task trees dynamically, `ford` works as a generalist worker, `arthur` and `eddie` coordinate the user session. The LLM call is a tool inside the engine logic.
- **Project Kits.** A Git bundle of skills, recipes, tools, and settings. `kit install` makes a project productive immediately. Pre-configured kits are possible (e.g. `kernel-security`, `python-data-science`); tenants maintain their own.
- **Customizable per project.** Recipes, prompts, tools, settings — everything cascades from bundled defaults → tenant → project. Each project overrides selectively, without touching code.
- **Scopes from day one.** Tenant → Project Group → Project → Session → Think Process. Memory cascades downward and is laterally isolated. Permissions, quotas, and settings attach to the scope.
- **Multiple clients, one Brain.** CLI (`vance-foot`), web UI (`vance-face`), mobile (`vance-fingers`). Brain is the single source of truth; clients are different access surfaces — not views on the same content.

## What Vance is not

Not a document editor, not a project management tool, not a notebook, not a Slack replacement. Vance exports results to Google Docs, Jira, Obsidian, etc. — managing those artifacts happens there, not in Vance.

## Concepts in one line

| Term | Meaning |
|---|---|
| **Engine** | Java algorithm with a lifecycle. Session layer (`arthur`, `eddie`), workers (`ford`, `marvin`, `vogon`, `zaphod`, `jeltz`), meta-engines that generate recipes (`slartibartfast`, `hactar`), service engine for tool health diagnosis (`agrajag`, classifies tool failures via LLM), plus workflow runtime `magrathea`. |
| **Recipe** | YAML config: engine + default params + prompt prefix + tool adjustments. Many, no code change. |
| **Think Process** | Running instance, persisted in Mongo. Status, task tree, inbox. |
| **Project Kit** | Git repo with skills/recipes/tools/settings, imported into a project. |
| **Scope** | Tenant/group/project/session/process — visibility for memory and permissions. |

## Tech stack

Java 25 + Spring Boot 4 + MongoDB + langchain4j/langgraph4j (Brain) · TypeScript + Vue 3 + Vite (web) · React Native + Expo (mobile) · Picocli + JLine 3 + Lanterna (CLI).

## Status

In active development. Brain, CLI, and web UI run locally; eleven engines are implemented: `arthur`, `eddie`, `ford`, `marvin`, `vogon`, `zaphod`, `jeltz`, `slartibartfast`, `hactar`, `magrathea`, `agrajag`. Tenants and permissions are prepared in the data model; the full multi-user layer comes later.

## License

Vance Non-Commercial, Non-Production Copyleft v1.0 — see [`LICENSE.txt`](LICENSE.txt). Use only for non-commercial, non-production purposes (testing, evaluation, research). Enterprise features (SSO, audit, team management) are available separately under a commercial license: [`vance-ee`](https://github.com/mhus/vance-ee).
