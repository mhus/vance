# Changelog

All notable changes to Vance are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
While the version is below `1.0.0`, a minor bump (`0.1` → `0.2`) may include
breaking changes; a patch bump (`0.1.0` → `0.1.1`) is fixes only.

## [Unreleased]

### Added

- **Bistromath** — application runtime where a view is a document
  (`$meta.kind: app-view`) and the behavior lives in `main.js`, running sandboxed
  in a null-origin iframe. Programs access documents and REST through `vance.rest`,
  five bundled libraries (`core@1`/`api@1`/`db@1`/`fmt@1`/`ui@1`), lifecycle hooks
  in the `onApp*` namespace, and a `vance.view.patch` write path with version
  memory. Apps are built by agents via the `app-builder` recipe; app governance
  (`_vance/config/applications.yaml`, default `forbidden`) gates every app with a
  per-project approval request through the inbox.
- **Trillian** — agentic user loop with two sessions (control owned by a human,
  user-loop owned by a headless service account). The Nature decides what a
  wakeup is worth; Nature-A `adam` is the first persistent Trillian with a
  journal, reflection, and a worker that pauses on a question instead of
  ending. Asking gets its own tool and engine; the heartbeat adopts loops that
  fell out of the schedule.
- **Jaglan** — mounted docs: foreign files appear under `_ext/<mount>/<path>`
  and are read with `doc_read`/embeds/WebDAV/Cortex. Protocols: `local`
  (read-only host directory) and `ode` (foreign application as mount source).
  Listings are complete (no cursor on the wire); a cut at 5000 entries per
  folder prevents a page from deleting everything behind it.
- **Maximegalon** — the inbox entity is an *Anliegen with at most one decision*
  plus the path to it. Three independent axes (`answered`/`archived` at the
  thread, `read` at the readable unit) instead of status values; agents can
  read the inbox and contribute to a thread; answer count and reactions in the
  inbox list; a "Diskussion" tab in Cortex shows the threads to the open
  document.
- **Centauri — Mastodon** — first `FREEFORM` feed source (public/hashtag
  timelines, app token per instance), as its own addon. `publishedAt` derived
  from the Snowflake-Id because `created_at` is not the stream order.
- **Project & user maintenance** — `project delete|rename` and `user
  delete|rename` in anus via a `*DataHandler` SPI per entity. Drain first
  (engines stop, workspace snapshotted, lease released), typed project name
  before delete *and* rename, handler idempotency, and a drift test + runtime
  sonde. User maintenance: `OWNED`/`RECORD`/authority classes decided per
  handler, tombstone `_deleted_<name>` on records to survive name reuse.
- **Kits** — `ode` as a fifth kit source type; kits can deliver credentials
  (`encoding: plain`, ode-only); `overwriteSecrets` lets an update replace a
  credential; authority levels in the update path; settings-deny for kits in
  `agentWriteDenyKeys`. `project:<name>` as a source type turns a project into a
  kit source.
- **Run view** — instances of all runtimes (Magrathea runs, plan-shaped
  think-processes, Compose runs) under one surface at `/runs`, with control
  (pause/resume/stop) and a project picker. Addressing `<source>:<nativeId>`.
- **Cluster placement** — placement centralized with labels and selectors,
  demand reporting / drain / "waiting for a pod" visible in the UI,
  placement round on demand instead of on a tick, `lifecycleType` as an
  operator concern (`/internal`, anus, UI read-only).
- **`vance-workflow` document kind** — flow renderer, kind picker entry,
  document template; a start button on the workflow document in Cortex;
  `agent_task` drives and terminates engines that don't do it themselves;
  cause-blind networks against stale runs.
- **Selection reference** — what a message *meant* survives the turn:
  `ActiveAppContext.selectionRef` (label + at least one address) is persisted
  per message and replayed in the history.
- **Links app** (`app: links`) — link manager for external URLs, manifest-
  anchored like the binder; `title` is a snapshot, `teaser`/`image` come live
  from the link-preview proxy unless someone typed them.
- **Inter-links** — a link points to a *place in an app* (`vance:/<folder>/_app.yaml?entry=<handle>`), not just a document; the handle is opaque and
  app-eigen, late-bound (an unresolvable handle opens the app and stays).
- **App governance** — `_vance/config/applications.yaml` (tenant-only),
  levels `forbidden`/`restricted`/`allowed` global/pro-project/per-path-prefix,
  default `forbidden`; enforcement fully in the client; approval request via
  inbox effect, proposal frozen.
- **Foot remote control per project** — `vance.remote.mode` in
  `.vancetope/config.yaml` overlay.
- **Light-LLM REST route** — generic REST endpoint with recipe approval
  `web: true`; consumed by Bistromath apps.
- **Worker from the web** — processes spawned from the web UI, shared spawn
  core with the CLI path.
- **Vogon** — an order arrives as a message, a plan also via path; a runner
  under Vogon and Magrathea.
- **Project copy** — documents and settings, nothing else.
- **Cortex** — folder tree loads per level instead of the whole project;
  right-panel switcher (sessions + help as one control); print layer
  (`data-print-root` for chat and cortex).
- **Milliways** — a share can choose a place in the app (intake targets).
- **GTD** — drag & drop into the sidebar plus project move.
- **Canvas** — the link node gets the shared dialog, becomes clickable and
  changeable.
- **Documents** — `If-Match` on the content `PUT` (optional, 412 on mismatch).

### Changed

- Client stack: Spring Boot 4.1.1, langchain4j 1.19, Anthropic 2.57, Milton 4.2.
- Face: Cortex, Chat, Inbox, Documents and the Landing become one cluster —
  switching between them does not reload the page and keeps the WebSocket.
- CodeMirror, KaTeX and markmap leave the eager barrel; addons load when their
  kind is opened, not on every page switch; addon remotes fetched in parallel.
- Anus: pod actions moved into a service, package on `anus.cluster`; project
  cluster actions extracted from the commands into a service.
- Maintenance: the two collectors moved to anus (no REST surface, no LLM tool —
  the typed confirmation is half the safety, and a terminal is where it lives).

### Fixed

- All placeholders in the tree on kebab-case (28 keys) — a placeholder is
  resolved by `Environment.resolvePlaceholders`, not the binder, and a name
  with uppercase is not a valid property name. `@ConditionalOnProperty` was
  affected.
- Two pods may boot against a fresh database simultaneously.
- Client tools stay DOWN after a reconnect no longer; a kind-Id is not a
  filename; a sanitized fragment could not post data outside; the ASK_USER
  button in the side chat swallowed the click.
- Trillian: a parked worker's question has to be pushed; a worker that asked
  must see that it asked; archive/reactivate keeps identity, grants and
  attributes; deleting an archived session releases its account too.
- Magrathea: the watchdog takes the master lease; `timeoutSeconds` also applies
  to `agent_task` and `workflow_task`.
- Cortex: going into a chat and back out no longer reloads the page; raw
  history writes no longer destroy the router state; inline math renders inline
  and deep links keep their query.
- Foot: the reconnect-resume test waited on the wrong place.
- OpenAI-experimental provider (Responses API) and qwen3.8 Cortecs models.

## [0.2.0] - 2026-08-12

### Added

- **Completion guard** — a generic, script-based post-completion check that can
  judge an engine's yield and inject a follow-up instead of stopping. Configured
  per recipe (`guard:`) or at runtime (`//guard script|inline|status`), wired
  into Arthur and Eddie.
- **Engine commands** — a `//verb` control-plane channel to a running process
  (`//llm`, `//thinking`, `//guard`, `//scratchpad`, `//trillian`, skill
  activate/deactivate), separate from the conversation.
- **Reasoning visibility** — model "thoughts" stream live into foot and the web
  UI, and are shown by default in foot.
- **Attachments** — `/attach` stages local files for the next message,
  tool-produced images reach the model, and every engine accepts attachments.
- **Scratchpad** — per-process slots with an inventory in the prompt, available
  to Frankie, Ford and the coding recipe.
- **Skills** — invocation arguments, shot lifecycle as a prompt macro,
  `action:` fires a turn on activation, and `run.target: spawn` runs a skill in
  a fresh worker.
- **Vault** — settings-backed secret vault as the default provider, plus the
  `vault:` reference scope.
- **Settings `HIDDEN`** — encrypted like `PASSWORD` but resolvable from dynamic
  elements (scripts, compose, agents); `PASSWORD` is now agent-invisible in both
  directions (read guard and write guard).
- **Schema migrations** — MongoDB migration framework with a registry, markers,
  a cluster lease and boot ordering; baseline for databases without markers.
- **Tool-surface budget** — the turn's tool manifest is fitted to the endpoint's
  `maxTools` limit, with measured per-role demand in a new Insights tab.
- **Password policy** — minimum length, BCrypt cost unified in `PasswordService`,
  brute-force lockout, and self-service password change.
- **Approval-gated permissions** — permission requests routed through the inbox
  as effects.
- **Documents** — copy (including cross-project), `file_delete` with its own
  client sandbox domain, and a central `DocumentRefResolver` for `vance:`
  references.
- **Sessions** — move a session to another project in place.
- **Formula documents** — new `formula` kind, and KaTeX/mhchem math rendering in
  the web UI's Markdown view.
- **Process visibility** — live process counters over WebSocket plus a detail
  and control view in face and foot.
- **Foot** — `/me`, `/exit`, `-c/--continue` from local session history,
  `/ui-exec` job browser, configurable UI colors, idle-triggered ghost-text
  follow-up suggestions, conversation capture, project-local tool packs behind a
  consent gate, and a `.vancetope/config.yaml` overlay.
- **`params.aiScope`** pins a recipe's AI configuration to the tenant layer.

### Changed

- Tool naming sweep: `tool_list` / `tool_description` replace
  `find_tools` / `describe_tool`, and the `doc_*` / `file_*` / `exec_*` families
  use one concept per parameter name (`projectId`, not `project`).
- Recipe routing picks semantically via LLM instead of matching recipe names.
- `_vance` is no longer usable as a `projectId`.
- Anus CLI migrated to Spring Shell 4.
- Client stack upgraded: Vite 8, Tailwind 4 + daisyUI 5, Tiptap 3, Pinia 4,
  vue-i18n 11, vue-tsc 3, TypeScript 7 via the TS6 compat package.
- Generated compose files require `VANCE_ENCRYPTION_PASSWORD`, and the internal
  shared secret no longer ships with a default.
- Maven reactor builds in parallel (`-T 1C`, Surefire `forkCount=4`).
- Documentation moved to www.vancetope.com; `VANCETOPE.md` is recognized as an
  agent doc alongside `AGENT.md`/`CLAUDE.md`.

### Removed

- `@vance/vance-fingers` (React Native client) — superseded by
  `facelift-bridge`.
- Frankie's post-completion hook — the completion guard covers it.
- Ford's `respond` tool; the engine terminates on a natural stop instead.

### Fixed

- `vance-anus` failed to start whenever Redis was enabled: Spring Boot 4's Redis
  auto-configuration contributed a second listener container and template, making
  the `vance*` bean lookups ambiguous. Brain already excluded it; anus does now
  too.
- `vance-anus --setup` / `--sudo` dropped into the interactive REPL instead of
  running the one-shot: Spring Shell 4 starts the shell from an
  `ApplicationRunner`, which runs before `ApplicationReadyEvent`.
- WebSocket robustness: server-side keep-alive ping with stale eviction, foot
  auto-reconnects and re-adopts its bound session, and a bind lease no longer
  kills a live connection.
- OpenAI-compatible endpoints: assistant `content:null` is stripped for
  providers that reject it, reasoning dialects are catalog facts, empty
  streaming completions are retried, timeouts scale with context, and
  `finish=LENGTH` is no longer treated as a transient glitch.
- ESC and pause take effect mid-loop in Arthur, Ford, Marvin, Vogon, Zaphod,
  Zarniwoop research calls, Damogran and Fenchurch.
- Tool failures are unambiguous to the model, and turn errors are no longer
  swallowed silently.
- Setting forms: an empty input resolves against the cascade layer, and a
  project-scoped write without a `projectId` lands in `_tenant`.
- Charts surface parse errors instead of rendering a blank canvas.

## [0.1.0] - 2026-07-30

### Added
- Initial public release scaffolding.
- CLI (`vance-foot`) distribution: a fat-jar asset on each GitHub release, a
  Homebrew tap (`brew install mhus/vancetope/vancetope` — bundles OpenJDK 25 as a
  dependency, no system Java required), and self-contained Java-free bundles
  per platform (macOS arm64/x64, Linux x64, Windows x64).

### Changed

### Fixed
