# Changelog

All notable changes to Vance are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
While the version is below `1.0.0`, a minor bump (`0.1` → `0.2`) may include
breaking changes; a patch bump (`0.1.0` → `0.1.1`) is fixes only.

## [Unreleased]

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
