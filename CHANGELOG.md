# Changelog

All notable changes to Vance are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
While the version is below `1.0.0`, a minor bump (`0.1` → `0.2`) may include
breaking changes; a patch bump (`0.1.0` → `0.1.1`) is fixes only.

## [Unreleased]

### Added

- **Vance Capture** — browser extension for Chrome, Firefox, and Safari:
  links jump into the web UI (link list + grabbed document), a second button
  imports the displayed page itself, and one extension can serve multiple
  targets. Store packages (AMO source archive, Safari project) build with a
  single command.
- **Integration tokens** — long-lived, narrowed credentials for foreign tools:
  an `scp` profile plus a project `pid` pin per token; a token can carry
  multiple profiles, and revocation checks the project pin against lost updates.
- **Web grab** — `POST /grab` imports the page the browser shows as a document
  (HTML to Markdown, no outgoing request).
- **Age encryption** — age-encrypted documents where the server stores only
  ciphertext and the key stays with the user: `vance-age` as the Java facade
  (jagged), `@vance/age` as the TS wrapper, decrypt/edit/convert in Cortex,
  decryption in the foot view (passphrase or identity file), and `doc_encrypt`
  as an agent tool that checks the write gate before encrypting.
- **Benjy** — iterative orchestration engine for small models: the task queue
  is the state; safety nets measure progress and cost, not volume; a structural
  item-charge cap; todos projection with `todos-updated`, journal records rendered
  as markdown, and a delegation manual for spawning orchestrators.
- **Zaphod session mode** — reactive council chat per user turn, with head
  replies as interim notes, a pinned synthesis model, a philosophical-council
  recipe, and a council category for all Zaphod recipes.
- **Shooty guard system** — the completion guard becomes a guard system with
  points: a START point in Ford (Trillian inherits Frankie's wiring) whose
  guard can replace the turn prompt entirely.
- **Workbook forms and quizzes** — `vance-field` block and a button action
  registry; quiz UX with reset, resolve, and persistent score; free-text
  answers graded by an internal form-judge LLM profile.
- **Report themes** — per-customer CSS themes with a PDF export menu in
  Cortex, a server-side theme-css endpoint, and a themed markdown preview
  in the web UI.
- **Scheduled model discovery** — opt-in via `vance.ai-models.discovery.enabled`;
  captures model info and endpoint pricing, and writes prices as `auto:true`
  manual docs (discovery and pricing stay source-separated).
- **AI configuration** — provider instances configurable per endpoint,
  `tlsInsecure` sidecar flag for private-CA gateways, a `default:chat` alias
  for chat surfaces, the `ai_model_current` tool, and creator support for
  model-definition setup without operator detours.
- **File tools** — If-Match guard: `contentHash` on `file_read`,
  `expectedContentHash` on `file_edit`/`file_write`.
- **Tools** — `defaults_list`/`defaults_read` for bundled vance-defaults,
  `location_get` as a client tool (browser-native permission state), and
  `doc_write` in the analyze recipe.
- **Follow-up** — optional FIM-completion path for the edit mode.
- **Cortex** — menu mount points (View/Actions/Extras) and selection translate
  for every text document.
- **Inbox** — compose a message into a user's or team's inbox; finished items
  return to the list.
- **Chat** — composer card (centered single-line input, ¶ toggle, auto-grow)
  and a skills tab in the right panel (list, active markers, play-to-composer).
- **Foot** — `/new` and `/ui-new` start a session from the recipe picker.
- **Recipe picker** — category grouping, a slim search field, and a projectKind
  filter (Eddie only in hub pickers, project recipes only in project pickers).
- **UI theming** — tenant-wide web-UI customization: custom CSS and a header logo.
- **Eddie** — the working-project spot is visible to LLM and user (list marking
  plus live push).
- **GTD** — capture field with auto-focus, drag-sort within a bucket list, and
  a trash bucket swept on rebuild instead of deleting on check-off.
- **Web UI** — project memory per browser tab, and visibility levels (Run view
  on, Store down).
- **`qrcode` kind** — payload body rendered as a scannable QR symbol.
- **Setup agent mode** — both anus setup wizards are headless-driveable via
  YAML config, fail-closed.
- **Empty-response diagnostics** — phantom tool call detection and reporting.
- **Marvin** — bounded parse-error correction loop for phase outputs.
- **Creator** — source analysis with version stamps, `brain_info`, and
  `git_checkout` commit/depth; BenjyArchitect for Slart authoring of Benjy
  outer-recipes.
- **Facelift** — Capacitor 8 toolchain, optional PIN lock, Play Store release
  signing, real Android icons and splash, and i18n across the federation
  boundary.
- **Build hygiene** — ESLint in the client workspace (lint fails the build),
  Spotless formatting for Java sources (palantir-java-format, ratcheted from
  origin/main), and PMD static analysis with a curated ruleset, baseline, and
  six adoption rounds.

### Changed

- "Vance" replaced by "Vancetope" in user-visible titles; license contact
  email filled in (info@vancetope.com).
- German strings swept out of the English surface (Workbook, Binder, and the
  UI at large).
- Client dependencies: js-yaml 5 (namespace import), Electron 44,
  `@types/node` 26, npm minor/patch group; Actions setup-java 6, Maven
  minor/patch group.
- Arthur prompt: Slartibartfast only via manual, never a blind preset; the
  DISCOVER trigger sharpened (known words as placement metaphers are
  discovery cases).
- Trillian: the recipe alias removed — `void` and `adam` stand side by side.

### Fixed

- Workbook: typing no longer vanishes on auto-save (silent quiet-window
  probe), quiz action writes survive the self-write window, and the page list
  becomes a drawer on phone width.
- Brain: a worker-turn exception closes INCOMPLETE and wakes the parent;
  deferred tools only via activate-first (direct calls on unlisted names die
  on restricted endpoints); `AiModelResolver` resolves named provider
  instances in the tenant default; `modelAlias` builds via provider instance
  instead of wire name; `AgeContentException` returns 400 instead of 500 on
  `PUT /content`.
- Usage: summary without `$dateTrunc` (MongoDB 4.4 compatibility), and one
  failing report cut no longer blanks the tab.
- Face: chat auto-scroll only when the user is reading at the end; the process
  panel shows terminated processes by default; the plan-box closure notice
  keeps the final list in the transcript; login no longer fails on iOS
  auto-capitalization; the Compose dialog shows no stale error; block editor
  empty document, save echo, and modal handle; the Cortex router writes the
  address before the handoff reads it.
- Voice: numbered lists spoken with ordinal connectors.
- Facelift: `/config.json` verify routed through the main process (CORS), the
  iOS generators no longer revert names and the speech key, and the adaptive
  icon uses the real assets.
- Benjy: journal state, wallclock phase, and empty-state guard.
- Zaphod: silent session heads fixed.
- Vogon: bundled intake/workflow examples follow the current schema.
- Settings: the encrypted write path drops the description; only a
  foot-backend registration counts as a CLIENT work target.
- Capture: match patterns without ports, Safari loads module scripts, a
  missing expiry reads "unknown" instead of "never", and an honest 401
  message.
- Web grab and project memory: five conversion review findings fixed, and
  project-memory seeding runs in its own watcher.

## [0.3.0] - 2026-08-28

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
