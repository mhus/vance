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
  AgentPolicy,
  AgentRuleRequest,
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
const SHELL_WORKDIR_GET = 'desktop-shell:workdir:get';
const SHELL_WORKDIR_SET = 'desktop-shell:workdir:set';
const SHELL_WORKDIR_PICK = 'desktop-shell:workdir:pick';
const SHELL_CONFINE_GET = 'desktop-shell:confine:get';
const SHELL_CONFINE_SET = 'desktop-shell:confine:set';
const SHELL_POLICY_GET = 'desktop-shell:policy:get';
const SHELL_POLICY_ADD = 'desktop-shell:policy:add-rule';
const SHELL_POLICY_REMOVE = 'desktop-shell:policy:remove-rule';
const SHELL_POLICY_RESET = 'desktop-shell:policy:reset';
const SHELL_POLICY_SANDBOX = 'desktop-shell:policy:set-sandbox';

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
  // Per-account working directory (planning/desktop-agent-tools.md §9) —
  // the CWD of the agent tools. Pick opens the native directory chooser.
  workdirGet: (o: { accountId: string }): Promise<string> =>
    ipcRenderer.invoke(SHELL_WORKDIR_GET, o),
  workdirSet: (o: { accountId: string; workdir: string }): Promise<void> =>
    ipcRenderer.invoke(SHELL_WORKDIR_SET, o),
  workdirPick: (o: { accountId: string }): Promise<string | null> =>
    ipcRenderer.invoke(SHELL_WORKDIR_PICK, o),
  // Confinement: paths outside the workdir deny instead of asking.
  confineGet: (o: { accountId: string }): Promise<boolean> =>
    ipcRenderer.invoke(SHELL_CONFINE_GET, o),
  confineSet: (o: { accountId: string; confined: boolean }): Promise<void> =>
    ipcRenderer.invoke(SHELL_CONFINE_SET, o),
  // Sandbox policy management: view, add, revoke, reset (§5).
  policyGet: (o: { accountId: string }): Promise<AgentPolicy | null> =>
    ipcRenderer.invoke(SHELL_POLICY_GET, o),
  policyAddRule: (o: AgentRuleRequest): Promise<AgentPolicy | null> =>
    ipcRenderer.invoke(SHELL_POLICY_ADD, o),
  policyRemoveRule: (o: AgentRuleRequest): Promise<AgentPolicy | null> =>
    ipcRenderer.invoke(SHELL_POLICY_REMOVE, o),
  policyReset: (o: { accountId: string }): Promise<void> =>
    ipcRenderer.invoke(SHELL_POLICY_RESET, o),
  // Master switch (foot's --no-sandbox equivalent): off = ungated.
  // The UI guards this with an explicit two-step confirmation.
  policySetSandbox: (o: { accountId: string; sandbox: boolean }): Promise<AgentPolicy | null> =>
    ipcRenderer.invoke(SHELL_POLICY_SANDBOX, o),
};

contextBridge.exposeInMainWorld('faceliftDesktop', bridge);
