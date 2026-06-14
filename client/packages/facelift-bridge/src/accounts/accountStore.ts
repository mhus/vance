import { Preferences } from '@capacitor/preferences';
import { VanceAccountWebView } from '@vance/facelift-account-webview';

/**
 * One account = one Vance deployment (the URL that serves
 * `vance-face`) the user has chosen to point this device at, plus
 * an optional human-friendly label. **No tokens, no usernames, no
 * passwords** — authentication happens inside the WebView once the
 * shell loads the website pointed at `faceUrl`, exactly as it would
 * in a desktop browser.
 *
 * The Vance brain is reachable from this same URL via the `/brain/*`
 * paths that `vance-face`'s nginx proxies — that's why we don't
 * store a separate brain URL.
 *
 * Origin-isolation comes for free across different `faceUrl` values:
 * iOS keeps cookies, IndexedDB, Service-Worker and LocalStorage
 * separated by origin. Multiple accounts on the **same** origin (two
 * users on `https://eddie.mhus.de`) are isolated by the
 * `@vance/facelift-account-webview` plugin's per-account
 * `WKWebsiteDataStore(forIdentifier:)`.
 */
export interface Account {
  id: string;
  /** URL of the `vance-face` deployment. Always served same-origin
   *  with its brain via the nginx `/brain/*` proxy. */
  faceUrl: string;
  displayName: string;
  createdAt: number;
  lastUsedAt: number;
}

const KEY_LIST = 'vance.facelift.accounts';
const KEY_ACTIVE = 'vance.facelift.activeAccountId';

function generateId(): string {
  return crypto.randomUUID();
}

export async function listAccounts(): Promise<Account[]> {
  const { value } = await Preferences.get({ key: KEY_LIST });
  if (value === null) return [];
  try {
    const parsed = JSON.parse(value) as Account[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

async function saveAll(accounts: Account[]): Promise<void> {
  await Preferences.set({ key: KEY_LIST, value: JSON.stringify(accounts) });
  await pushSnapshotToShareExtension(accounts);
}

/**
 * Mirror the account list into the App-Group container so the iOS
 * Share-Extension target can populate its account picker. Stripped
 * to the fields the extension actually needs ({@code id, faceUrl,
 * displayName}) — credentials + project list come from the website
 * via separate bridge calls. Silently no-ops when the App Group
 * isn't configured yet (first install before the user has added the
 * capability in Xcode).
 */
export async function pushSnapshotToShareExtension(accounts: Account[]): Promise<void> {
  try {
    const snapshot = accounts.map((a) => ({
      id: a.id,
      faceUrl: a.faceUrl,
      displayName: a.displayName,
    }));
    await VanceAccountWebView.setAccountSnapshot({
      accountsJson: JSON.stringify(snapshot),
    });
  } catch (e) {
    console.warn('[facelift] account snapshot push failed', e);
  }
}

export async function getAccount(id: string): Promise<Account | null> {
  const accounts = await listAccounts();
  return accounts.find((a) => a.id === id) ?? null;
}

export async function addAccount(input: {
  faceUrl: string;
  displayName?: string;
}): Promise<Account> {
  const faceUrl = input.faceUrl.trim().replace(/\/+$/, '');
  if (faceUrl.length === 0) throw new Error('URL is required.');
  const displayName =
    input.displayName?.trim() && input.displayName.trim().length > 0
      ? input.displayName.trim()
      : defaultDisplayName(faceUrl);
  const now = Date.now();
  const account: Account = {
    id: generateId(),
    faceUrl,
    displayName,
    createdAt: now,
    lastUsedAt: now,
  };
  const accounts = await listAccounts();
  accounts.push(account);
  await saveAll(accounts);
  // Always activate the freshly added account — the user just chose
  // to add it, so the shell should open *its* website rather than
  // dropping them back into whichever account was previously active.
  await setActiveAccountId(account.id);
  return account;
}

export async function updateAccount(
  id: string,
  patch: { faceUrl?: string; displayName?: string },
): Promise<{ account: Account; faceUrlChanged: boolean } | null> {
  const accounts = await listAccounts();
  const target = accounts.find((a) => a.id === id);
  if (target === undefined) return null;

  const newFaceUrl =
    patch.faceUrl !== undefined
      ? patch.faceUrl.trim().replace(/\/+$/, '')
      : target.faceUrl;
  if (newFaceUrl.length === 0) throw new Error('URL is required.');

  const newDisplayName =
    patch.displayName !== undefined && patch.displayName.trim().length > 0
      ? patch.displayName.trim()
      : defaultDisplayName(newFaceUrl);

  const faceUrlChanged = newFaceUrl !== target.faceUrl;
  target.faceUrl = newFaceUrl;
  target.displayName = newDisplayName;
  await saveAll(accounts);
  return { account: target, faceUrlChanged };
}

export async function removeAccount(id: string): Promise<void> {
  const remaining = (await listAccounts()).filter((a) => a.id !== id);
  await saveAll(remaining);
  const active = await getActiveAccountId();
  if (active === id) {
    // Hand the active pointer to the most-recently-used remaining
    // account, or clear it if none are left.
    const next = [...remaining].sort((a, b) => b.lastUsedAt - a.lastUsedAt)[0];
    await setActiveAccountId(next?.id ?? null);
  }
}

export async function touchAccount(id: string): Promise<void> {
  const accounts = await listAccounts();
  const target = accounts.find((a) => a.id === id);
  if (target === undefined) return;
  target.lastUsedAt = Date.now();
  await saveAll(accounts);
}

export async function getActiveAccountId(): Promise<string | null> {
  const { value } = await Preferences.get({ key: KEY_ACTIVE });
  return value === null || value.length === 0 ? null : value;
}

export async function setActiveAccountId(id: string | null): Promise<void> {
  if (id === null) {
    await Preferences.remove({ key: KEY_ACTIVE });
    return;
  }
  await Preferences.set({ key: KEY_ACTIVE, value: id });
  await touchAccount(id);
}

function defaultDisplayName(faceUrl: string): string {
  try {
    return new URL(faceUrl).host;
  } catch {
    return faceUrl;
  }
}
