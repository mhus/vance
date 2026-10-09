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
}

/** IPC channel names used by the tools bridge (preload ↔ main). */
/** IPC channel names used by the account-WebView tools bridge (preload ↔
 *  main). The account bridge is read-only for the release switch — the
 *  write path is shell-only (see {@link SHELL_IPC}). */
export const IPC = {
  invoke: 'desktop-tools:invoke',
  getContext: 'desktop-tools:get-context',
  toolsEnabledGet: 'desktop-tools:tools-enabled:get',
} as const;

/** IPC channels for the shell renderer's release button: get/set per
 *  account + the activity push (planning/desktop-agent-tools.md §6.2). */
export const SHELL_IPC = {
  toolsEnabledGet: 'desktop-shell:tools-enabled:get',
  toolsEnabledSet: 'desktop-shell:tools-enabled:set',
  toolsActivity: 'desktop-shell:activity',
} as const;

/** Contract version reported through the bridge. Bump on breaking changes. */
export const BRIDGE_VERSION = '1';
