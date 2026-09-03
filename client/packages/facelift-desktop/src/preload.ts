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
  UrlOpenEvent,
} from './types';

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
};

contextBridge.exposeInMainWorld('faceliftDesktop', bridge);
