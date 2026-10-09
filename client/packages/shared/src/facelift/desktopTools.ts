/**
 * The Facelift desktop (Electron) tool bridge — the capability contract
 * for the desktop-only `client_file_*` / `client_exec_*` agent tools
 * (see `planning/desktop-agent-tools.md`).
 *
 * The bridge is injected by the `facelift-desktop` preload into every
 * account WebView. Its presence is the **capability gate**: registration
 * of the local tool families keys on {@link getDesktopTools} returning a
 * bridge, never on User-Agent sniffing (`isFaceliftDesktop()` is UI
 * cosmetics — a spoofed UA creates no bridge). Browsers, iPad and Android
 * never see this object.
 *
 * The bridge is deliberately thin: it forwards named operations and their
 * parameters to the Electron main process, which owns execution *and* the
 * permission gate. The renderer can request, never decide — results carry
 * an error and possibly a `denyReason`, never the verdict internals or the
 * policy.
 */

/** Platform context the main process reports about itself — mirrors the
 *  brain-side `ClientContext` DTO (`de.mhus.vance.api.ws.ClientContext`)
 *  that foot sends as the `X-Vance-Client-Context` handshake header. The
 *  desktop app transports it as the `?clientContext=` WS query parameter
 *  instead, because a browser WebSocket cannot set custom headers. */
export interface DesktopClientContext {
  os: string;
  arch: string;
  shell: string;
  cwd: string;
  sandboxEnabled: boolean;
  timezone: string;
}

/** Result of a bridged operation. */
export type DesktopToolInvokeResult =
  | { ok: true; result: Record<string, unknown> }
  | { ok: false; error: string; denyReason?: string };

/** The contract exposed as `window.vanceDesktopTools` via contextBridge. */
export interface DesktopToolsBridge {
  /** Contract version — bump on breaking changes. */
  version: string;
  /** Platform context for the environment prompt block. */
  getContext(): DesktopClientContext;
  /**
   * Read-only view of the per-account release — the web UI consults it
   * at bind time to decide the registration. The write path lives with
   * the trusted shell renderer; remote content can honor the release
   * but never flip it.
   */
  toolsEnabled: {
    get(): Promise<boolean>;
  };
  /**
   * Run one operation. `op` is a closed namespace (`file.*`, `exec.*`);
   * unknown ops are rejected with an error, never a fallthrough.
   */
  invoke(op: string, params: Record<string, unknown>): Promise<DesktopToolInvokeResult>;
  /**
   * Tool packs (MCP): the pack tools the account materialized — specs for
   * the registration plus the routing pair. Empty until (and after a
   * failed) materialization; a bad server never blocks the rest.
   */
  packs: {
    list(): Promise<DesktopPackToolEntry[]>;
  };
}

/** One pack tool from {@link DesktopToolsBridge.packs}: the spec the
 *  face registers (`<pack>__<tool>`, labels incl. `mcp`/`mcp:<pack>`) +
 *  the routing pair for `pack_invoke`. */
export interface DesktopPackToolEntry {
  spec: {
    name: string;
    description: string;
    primary: boolean;
    source: string;
    paramsSchema: Record<string, unknown>;
    labels: string[];
    allowedProfiles: string[];
    deferred: boolean;
    searchHint: string;
    safety: string;
    requiresEngineRoles: string[];
  };
  pack: string;
  tool: string;
}

const BRIDGE_KEY = 'vanceDesktopTools';

/**
 * The injected bridge, or `null` when not running inside the Facelift
 * desktop app. Presence = capability; use this as the gate, not the UA.
 */
export function getDesktopTools(): DesktopToolsBridge | null {
  if (typeof window === 'undefined') return null;
  const bridge = (window as unknown as Record<string, unknown>)[BRIDGE_KEY];
  return isBridge(bridge) ? bridge : null;
}

/** Structural guard — an injected bridge must expose all three members. */
function isBridge(value: unknown): value is DesktopToolsBridge {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<DesktopToolsBridge>;
  return (
    typeof candidate.version === 'string' &&
    typeof candidate.getContext === 'function' &&
    typeof candidate.toolsEnabled === 'object' &&
    candidate.toolsEnabled !== null &&
    typeof candidate.toolsEnabled.get === 'function' &&
    typeof candidate.invoke === 'function' &&
    // packs arrived with the tool-pack stage — an app build without it
    // still counts as a bridge (the face treats it as an empty toolbox)
    (candidate.packs === undefined ||
      (typeof candidate.packs === 'object' &&
        candidate.packs !== null &&
        typeof candidate.packs.list === 'function'))
  );
}
