/**
 * Main-process view of the desktop agent-tools contract. Mirrors the
 * renderer-facing {@code DesktopToolsBridge} in
 * `@vance/shared/facelift/desktopTools` — keep the two in lock-step
 * (same discipline as {@code faceliftDesktop} in preload.ts/types.ts).
 *
 * The ops are a closed namespace (`file.*` today, `exec.*` in stage 2).
 * Wire-level tool names map 1:1 onto them: `file.read` ↔
 * `client_file_read`, … — parity with the foot CLI is the contract
 * (planning/desktop-agent-tools.md §4.1).
 */

/** Result the renderer receives from one operation. */
export type InvokeResult =
  | { ok: true; result: Record<string, unknown> }
  | { ok: false; error: string; denyReason?: string };

/** Platform context reported to the hosted web UI. */
export interface DesktopClientContext {
  os: string;
  arch: string;
  shell: string;
  cwd: string;
  sandboxEnabled: boolean;
  timezone: string;
}

/** Per-account entry of the desktop tools config. */
export interface AccountToolsConfig {
  /** Release switch — default off, fail-safe. */
  toolsEnabled: boolean;
  /** Working directory relative paths resolve against. Default: home. */
  workdir: string;
  /** Confinement: paths outside the workdir deny instead of asking. */
  confineToWorkdir: boolean;
}

/** IPC channel names used by the tools bridge (preload ↔ main). */
/** IPC channel names used by the account-WebView tools bridge (preload ↔
 *  main). The account bridge is read-only for the release switch — the
 *  write path is shell-only (see {@link SHELL_IPC}). */
export const IPC = {
  invoke: 'desktop-tools:invoke',
  getContext: 'desktop-tools:get-context',
  toolsEnabledGet: 'desktop-tools:tools-enabled:get',
  packsList: 'desktop-tools:packs:list',
} as const;

/** IPC channels for the shell renderer: the release button (get/set per
 *  account + the activity push, planning/desktop-agent-tools.md §6.2) and
 *  the per-account working directory (§9 — display, change, native picker).
 *  The account WebViews never see these channels: config writes are
 *  shell-only, remote content reaches the tools config exclusively through
 *  the gate in the main process. */
export const SHELL_IPC = {
  toolsEnabledGet: 'desktop-shell:tools-enabled:get',
  toolsEnabledSet: 'desktop-shell:tools-enabled:set',
  toolsActivity: 'desktop-shell:activity',
  workdirGet: 'desktop-shell:workdir:get',
  workdirSet: 'desktop-shell:workdir:set',
  workdirPick: 'desktop-shell:workdir:pick',
  confineGet: 'desktop-shell:confine:get',
  confineSet: 'desktop-shell:confine:set',
  policyGet: 'desktop-shell:policy:get',
  policyAddRule: 'desktop-shell:policy:add-rule',
  policyRemoveRule: 'desktop-shell:policy:remove-rule',
  policyReset: 'desktop-shell:policy:reset',
  policySetSandbox: 'desktop-shell:policy:set-sandbox',
} as const;

/** Contract version reported through the bridge. Bump on breaking changes. */
export const BRIDGE_VERSION = '1';
