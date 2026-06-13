import { Preferences } from '@capacitor/preferences';

/**
 * One account = one Brain server the user has chosen to point this
 * device at, plus an optional human-friendly label. **No tokens, no
 * usernames, no passwords** — authentication happens inside the
 * WebView once the shell renders the iframe pointed at `brainUrl`,
 * exactly as it would in a desktop browser.
 *
 * Origin-isolation comes for free across different `brainUrl`
 * values: iOS keeps cookies, IndexedDB, Service-Worker and
 * LocalStorage separated by origin. Multiple accounts on the **same**
 * Brain origin (e.g. two users on `https://eddie.mhus.de`) currently
 * share cookies — Phase 2 considers per-account
 * `WKWebsiteDataStore` profiles.
 */
export interface Account {
  id: string;
  brainUrl: string;
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
}

export async function getAccount(id: string): Promise<Account | null> {
  const accounts = await listAccounts();
  return accounts.find((a) => a.id === id) ?? null;
}

export async function addAccount(input: {
  brainUrl: string;
  displayName?: string;
}): Promise<Account> {
  const brainUrl = input.brainUrl.trim().replace(/\/+$/, '');
  if (brainUrl.length === 0) throw new Error('Brain URL is required.');
  const displayName =
    input.displayName?.trim() && input.displayName.trim().length > 0
      ? input.displayName.trim()
      : defaultDisplayName(brainUrl);
  const now = Date.now();
  const account: Account = {
    id: generateId(),
    brainUrl,
    displayName,
    createdAt: now,
    lastUsedAt: now,
  };
  const accounts = await listAccounts();
  accounts.push(account);
  await saveAll(accounts);
  // Auto-activate when this is the first account so the shell has
  // something to render immediately.
  const active = await getActiveAccountId();
  if (active === null) await setActiveAccountId(account.id);
  return account;
}

export async function updateAccount(
  id: string,
  patch: { brainUrl?: string; displayName?: string },
): Promise<{ account: Account; brainUrlChanged: boolean } | null> {
  const accounts = await listAccounts();
  const target = accounts.find((a) => a.id === id);
  if (target === undefined) return null;

  const newBrainUrl =
    patch.brainUrl !== undefined
      ? patch.brainUrl.trim().replace(/\/+$/, '')
      : target.brainUrl;
  if (newBrainUrl.length === 0) throw new Error('Brain URL is required.');

  const newDisplayName =
    patch.displayName !== undefined && patch.displayName.trim().length > 0
      ? patch.displayName.trim()
      : defaultDisplayName(newBrainUrl);

  const brainUrlChanged = newBrainUrl !== target.brainUrl;
  target.brainUrl = newBrainUrl;
  target.displayName = newDisplayName;
  await saveAll(accounts);
  return { account: target, brainUrlChanged };
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

function defaultDisplayName(brainUrl: string): string {
  try {
    return new URL(brainUrl).host;
  } catch {
    return brainUrl;
  }
}
