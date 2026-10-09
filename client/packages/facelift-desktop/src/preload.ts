import { contextBridge, ipcRenderer, type IpcRendererEvent } from 'electron';

import type {
  AccountWebViewBounds,
  BiometricAvailability,
  BiometricResult,
  HttpGetOptions,
  HttpGetResult,
  NavigateHomeOptions,
  PresentOptions,
  RemoveOptions,
  ToolsActivityEvent,
  UrlOpenEvent,
} from './types';

// Shell agent-tools IPC channels — inlined as literals like every other
// channel above: a sandboxed preload cannot require sibling modules, only
// the whitelisted `electron` subset (see preload-tools.ts for the same
// discipline). Canonical constants live in tools/types.ts SHELL_IPC.
const SHELL_TOOLS_GET = 'desktop-shell:tools-enabled:get';
const SHELL_TOOLS_SET = 'desktop-shell:tools-enabled:set';
const SHELL_TOOLS_ACTIVITY = 'desktop-shell:activity';

// The renderer-facing bridge. Shape must match the FaceliftDesktopBridge
// interface in facelift-account-webview/src/definitions.ts (formerly in
// web.ts — moved so the `declare global` is visible to consumers).
const bridge = {
  present: (o: PresentOptions): Promise<void> =>
    ipcRenderer.invoke('facelift:present', o),
  dismiss: (): Promise<void> => ipcRenderer.invoke('facelift:dismiss'),
  setBounds: (o: AccountWebViewBounds): Promise<void> =>
    ipcRenderer.invoke('facelift:setBounds', o),
  reload: (): Promise<void> => ipcRenderer.invoke('facelift:reload'),
  navigateHome: (o: NavigateHomeOptions): Promise<void> =>
    ipcRenderer.invoke('facelift:navigateHome', o),
  remove: (o: RemoveOptions): Promise<void> =>
    ipcRenderer.invoke('facelift:remove', o),
  setAccountSnapshot: (o: { accountsJson: string }): Promise<void> =>
    ipcRenderer.invoke('facelift:setAccountSnapshot', o),
  setShareCredentials: (o: {
    accountId: string;
    credentialsJson: string;
  }): Promise<void> => ipcRenderer.invoke('facelift:setShareCredentials', o),
  setProjectSnapshot: (o: {
    accountId: string;
    projectsJson: string;
  }): Promise<void> => ipcRenderer.invoke('facelift:setProjectSnapshot', o),
  isBiometricAvailable: (): Promise<BiometricAvailability> =>
    ipcRenderer.invoke('facelift:isBiometricAvailable'),
  authenticateBiometric: (o: { reason?: string }): Promise<BiometricResult> =>
    ipcRenderer.invoke('facelift:authenticateBiometric', o),
  httpGet: (o: HttpGetOptions): Promise<HttpGetResult> =>
    ipcRenderer.invoke('facelift:httpGet', o),
  onUrlOpen: (callback: (event: UrlOpenEvent) => void): (() => void) => {
    const listener = (_e: IpcRendererEvent, event: UrlOpenEvent): void =>
      callback(event);
    ipcRenderer.on('facelift:urlOpen', listener);
    return () => ipcRenderer.removeListener('facelift:urlOpen', listener);
  },
  // Agent-tools release button (planning/desktop-agent-tools.md §6.2):
  // get/set per account + the activity push. Shell-only — the account
  // WebViews get a separate, read-only bridge.
  toolsEnabledGet: (o: { accountId: string }): Promise<boolean> =>
    ipcRenderer.invoke(SHELL_TOOLS_GET, o),
  toolsEnabledSet: (o: { accountId: string; enabled: boolean }): Promise<void> =>
    ipcRenderer.invoke(SHELL_TOOLS_SET, o),
  onToolsActivity: (callback: (event: ToolsActivityEvent) => void): (() => void) => {
    const listener = (_e: IpcRendererEvent, event: ToolsActivityEvent): void =>
      callback(event);
    ipcRenderer.on(SHELL_TOOLS_ACTIVITY, listener);
    return () => ipcRenderer.removeListener(SHELL_TOOLS_ACTIVITY, listener);
  },
};

contextBridge.exposeInMainWorld('faceliftDesktop', bridge);
