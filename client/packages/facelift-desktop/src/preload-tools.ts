/**
 * Preload for the **account WebViews** — injects the desktop agent-tools
 * bridge (`window.vanceDesktopTools`) into the hosted web UI. Distinct
 * from `preload.ts` (the shell renderer): account views load remote
 * content, so this bridge is the only capability they get — request-only,
 * every decision stays in the main process.
 *
 * The exposed shape must match `DesktopToolsBridge` in
 * `@vance/shared/facelift/desktopTools` — keep in lock-step. The IPC
 * channel names are deliberately inlined as literals (same discipline
 * as {@code preload.ts}): a sandboxed preload cannot {@code require}
 * sibling modules, only the whitelisted `electron` subset — a value
 * import from {@code ./tools/types} crashes the preload silently and
 * leaves the page without a bridge. The constants live canonically in
 * {@code ./tools/types.ts}; a rename must touch both.
 */
import { contextBridge, ipcRenderer } from 'electron';

const IPC_INVOKE = 'desktop-tools:invoke';
const IPC_GET_CONTEXT = 'desktop-tools:get-context';
const IPC_TOOLS_ENABLED_GET = 'desktop-tools:tools-enabled:get';
const IPC_PACKS_LIST = 'desktop-tools:packs:list';

contextBridge.exposeInMainWorld('vanceDesktopTools', {
  version: '1',
  getContext: (): Promise<Record<string, unknown>> => ipcRenderer.invoke(IPC_GET_CONTEXT),
  toolsEnabled: {
    get: (): Promise<boolean> => ipcRenderer.invoke(IPC_TOOLS_ENABLED_GET),
  },
  packs: {
    list: (): Promise<unknown[]> => ipcRenderer.invoke(IPC_PACKS_LIST),
  },
  invoke: (
    op: string,
    params: Record<string, unknown>,
  ): Promise<Record<string, unknown>> => ipcRenderer.invoke(IPC_INVOKE, op, params),
});
