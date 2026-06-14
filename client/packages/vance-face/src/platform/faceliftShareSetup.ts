/**
 * Push share-relevant snapshots from the website into the Facelift
 * App-Group container so the iOS Share-Extension can use them.
 *
 * The bridge is `window.vanceFacelift.setShareCredentials({...})` +
 * `setProjectSnapshot([...])`, injected by the Facelift Swift plugin
 * at WebView document-start. Each call is a no-op when the website
 * is running in a plain browser ({@link isFacelift} returns false)
 * or when the bridge JS hasn't loaded yet — UI code can call these
 * helpers unconditionally.
 */
import type { ProjectSummary } from '@vance/generated';
import { brainBaseUrl, isFacelift } from '@vance/shared';

interface FaceliftBridge {
  accountId?: string;
  setShareCredentials?(opts: ShareCredentials): void;
  setProjectSnapshot?(projects: SnapshotProject[]): void;
}

export interface ShareCredentials {
  /** URL of the `vance-face` deployment (same-origin host of the
   *  brain via the nginx `/brain/*` proxy). The Share-Extension
   *  POSTs `<faceUrl>/brain/{tenant}/share/inbox` with these
   *  credentials. */
  faceUrl: string;
  tenant: string;
  username: string;
  /** Bearer access token. */
  token: string;
  /** Refresh token — the extension uses it to re-mint expired
   *  access tokens without prompting the user. */
  refreshToken?: string;
}

interface SnapshotProject {
  name: string;
  title?: string;
}

function bridge(): FaceliftBridge | null {
  if (!isFacelift()) return null;
  const b = (window as unknown as { vanceFacelift?: FaceliftBridge })
    .vanceFacelift;
  return b ?? null;
}

/**
 * Forward the credentials minted at login (or silent refresh) into
 * the App-Group container under the current account's ID. Stored
 * for the iOS Share-Extension target to use as a bearer header.
 */
export function pushShareCredentials(opts: Omit<ShareCredentials, 'faceUrl'>): void {
  const b = bridge();
  if (b?.setShareCredentials === undefined) return;
  b.setShareCredentials({
    faceUrl: brainBaseUrl(),
    tenant: opts.tenant,
    username: opts.username,
    token: opts.token,
    refreshToken: opts.refreshToken,
  });
}

/**
 * Forward the project list to the App-Group container. Called from
 * `useTenantProjects.reload()` after a successful `/projects` fetch.
 * Stripped to `{name, title}` — the extension's project picker only
 * needs those two fields.
 */
export function pushProjectSnapshot(projects: ProjectSummary[]): void {
  const b = bridge();
  if (b?.setProjectSnapshot === undefined) return;
  b.setProjectSnapshot(projects.map((p) => ({ name: p.name, title: p.title })));
}
