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
  HttpGetOptions,
  HttpGetResult,
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

  ipcMain.handle('facelift:httpGet', (_e, o: HttpGetOptions) => httpGet(o));
}

/**
 * CORS-free HTTP GET via the Electron main process (Node `fetch`).
 *
 * The renderer-side `verifyVanceUrl` would otherwise go through
 * CapacitorHttp's web fallback (browser `fetch`), which enforces a CORS
 * preflight that a Vance deployment is not expected to answer — the
 * native iOS/Android path uses URLSession and never hits CORS. Routing
 * the Add-Account verification through the main process mirrors that
 * native path on the desktop.
 *
 * Returns a shape compatible with Capacitor's `HttpResponse` so the
 * shared verification logic consumes it unchanged.
 */
async function httpGet(options: HttpGetOptions): Promise<HttpGetResult> {
  const controller = new AbortController();
  const timer = setTimeout(
    () => controller.abort(),
    options.readTimeout ?? 5000,
  );
  try {
    const res = await fetch(options.url, {
      headers: options.headers,
      signal: controller.signal,
      redirect: 'follow',
    });
    const headers: Record<string, string> = {};
    res.headers.forEach((value, key) => {
      headers[key] = value;
    });
    const text = await res.text();
    const contentType = res.headers.get('content-type') ?? '';
    let data: unknown = text;
    if (contentType.toLowerCase().includes('json') && text.length > 0) {
      try {
        data = JSON.parse(text) as unknown;
      } catch {
        // Leave `data` as the raw text — verifyVanceUrl reports a
        // parse error with an excerpt, same as the Capacitor path.
      }
    }
    return { status: res.status, headers, data, url: res.url };
  } catch (e) {
    // Node's `fetch` throws a bare `TypeError: fetch failed`; surface
    // the cause (ENOTFOUND, ECONNREFUSED, TLS, …) so the user sees the
    // real reason in the "Not a Vancetope instance (...)" message.
    const cause = (e as { cause?: { message?: string } }).cause;
    const detail =
      cause?.message ?? (e instanceof Error ? e.message : 'network error');
    throw new Error(detail);
  } finally {
    clearTimeout(timer);
  }
}
