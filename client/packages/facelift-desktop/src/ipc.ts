import { ipcMain } from 'electron';

import { AccountViewManager } from './account-view-manager';
import { authenticateBiometric, isBiometricAvailable } from './biometric';
import {
  setAccountSnapshot,
  setProjectSnapshot,
  setShareCredentials,
} from './snapshots';
import type {
  AccountWebViewBounds,
  NavigateHomeOptions,
  PresentOptions,
  RemoveOptions,
} from './types';

/**
 * Wire the `facelift:*` IPC channels (invoked by the preload bridge) to
 * the main-process implementations. Registered exactly once at app start;
 * `getManager` supplies the current window's manager (which may be
 * re-created on macOS `activate`). Handlers return values/promises that
 * `ipcRenderer.invoke` resolves with.
 */
export function registerIpc(getManager: () => AccountViewManager | null): void {
  const withManager = <T>(fn: (m: AccountViewManager) => T): T | undefined => {
    const m = getManager();
    return m ? fn(m) : undefined;
  };

  ipcMain.handle('facelift:present', (_e, o: PresentOptions) =>
    withManager((m) => m.present(o)),
  );
  ipcMain.handle('facelift:dismiss', () => withManager((m) => m.dismiss()));
  ipcMain.handle('facelift:setBounds', (_e, o: AccountWebViewBounds) =>
    withManager((m) => m.setBounds(o)),
  );
  ipcMain.handle('facelift:reload', () => withManager((m) => m.reload()));
  ipcMain.handle('facelift:navigateHome', (_e, o: NavigateHomeOptions) =>
    withManager((m) => m.navigateHome(o)),
  );
  ipcMain.handle('facelift:remove', (_e, o: RemoveOptions) =>
    withManager((m) => m.remove(o)),
  );

  ipcMain.handle('facelift:isBiometricAvailable', () => isBiometricAvailable());
  ipcMain.handle(
    'facelift:authenticateBiometric',
    (_e, o: { reason?: string }) =>
      authenticateBiometric(o?.reason ?? 'Unlock Vancetope'),
  );

  ipcMain.handle(
    'facelift:setAccountSnapshot',
    (_e, o: { accountsJson: string }) => setAccountSnapshot(o.accountsJson),
  );
  ipcMain.handle(
    'facelift:setShareCredentials',
    (_e, o: { accountId: string; credentialsJson: string }) =>
      setShareCredentials(o.accountId, o.credentialsJson),
  );
  ipcMain.handle(
    'facelift:setProjectSnapshot',
    (_e, o: { accountId: string; projectsJson: string }) =>
      setProjectSnapshot(o.accountId, o.projectsJson),
  );
}
