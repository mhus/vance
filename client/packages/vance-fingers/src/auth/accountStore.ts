import * as SecureStore from 'expo-secure-store';
import { StorageKeys, getStorage } from '@vance/shared';

/**
 * Persistent multi-account inventory for the mobile client.
 *
 * <p>Storage layout:
 *
 * <ul>
 *   <li>{@code prefsStore[StorageKeys.accountsList]} — JSON-encoded
 *       {@link Account}{@code []}</li>
 *   <li>{@code prefsStore[StorageKeys.accountsCurrent]} — id of the
 *       active account (the one whose tokens / identity mirror into
 *       the flat keys)</li>
 *   <li>{@code secureStore[StorageKeys.authAccessToken + '.' + id]} —
 *       per-account access JWT backup</li>
 *   <li>{@code secureStore[StorageKeys.authRefreshToken + '.' + id]} —
 *       per-account refresh JWT backup</li>
 * </ul>
 *
 * <p>Plus the legacy flat keys ({@code vance.auth.accessToken},
 * {@code vance.identity.tenantId}, …) which the rest of the app reads
 * via {@code getTenantId()} / {@code getStorage().secureStore.get(...)}.
 * Those continue to hold the <i>active mirror</i> — the active
 * account's tokens and identity. Switching an account writes the
 * target account's data into those flat keys before triggering the
 * UI re-mount, so existing read-paths see the new account
 * automatically with no consumer-side refactor.
 *
 * <p>Subscribers (mainly {@code App.tsx}) get notified whenever the
 * inventory or the active-account pointer changes; that drives the
 * {@code <RootNavigator key={currentAccountId} />} re-mount trick.
 */
export interface Account {
  /** Stable random id, generated at first add. */
  id: string;
  /** Trailing-slash-trimmed brain URL. */
  brainUrl: string;
  tenantId: string;
  username: string;
  /** Optional human-friendly label; falls back to
   *  {@code username@tenantId} when undefined. */
  displayName?: string;
  /** Epoch ms — never updated after add. */
  createdAt: number;
  /** Epoch ms — bumped on every {@link setCurrent} call. */
  lastUsedAt: number;
}

const SUBSCRIBERS = new Set<() => void>();

function notify(): void {
  for (const cb of SUBSCRIBERS) {
    try {
      cb();
    } catch (e) {
      console.warn('accountStore subscriber threw', e);
    }
  }
}

/**
 * Listen for inventory / active-account changes. Returns the
 * unsubscribe function — call it from a {@code useEffect} cleanup.
 */
export function subscribe(cb: () => void): () => void {
  SUBSCRIBERS.add(cb);
  return () => {
    SUBSCRIBERS.delete(cb);
  };
}

export function listAccounts(): Account[] {
  const raw = getStorage().prefsStore.get(StorageKeys.accountsList);
  if (raw === null || raw.length === 0) return [];
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isAccount);
  } catch {
    return [];
  }
}

export function currentAccountId(): string | null {
  const id = getStorage().prefsStore.get(StorageKeys.accountsCurrent);
  return id !== null && id.length > 0 ? id : null;
}

export function currentAccount(): Account | null {
  const id = currentAccountId();
  if (id === null) return null;
  return listAccounts().find((a) => a.id === id) ?? null;
}

export function findAccount(
  brainUrl: string,
  tenantId: string,
  username: string,
): Account | null {
  const trimmed = trimUrl(brainUrl);
  return (
    listAccounts().find(
      (a) =>
        a.brainUrl === trimmed
        && a.tenantId === tenantId
        && a.username === username,
    ) ?? null
  );
}

/**
 * Add an account or update an existing entry's {@code lastUsedAt} /
 * {@code displayName}. Matched by the
 * {@code (brainUrl, tenantId, username)} triple — the same user on
 * the same brain in the same tenant is one account.
 */
export function upsertAccount(input: {
  brainUrl: string;
  tenantId: string;
  username: string;
  displayName?: string;
}): Account {
  const trimmed = trimUrl(input.brainUrl);
  const list = listAccounts();
  const now = Date.now();
  const existing = list.find(
    (a) =>
      a.brainUrl === trimmed
      && a.tenantId === input.tenantId
      && a.username === input.username,
  );
  if (existing !== undefined) {
    existing.lastUsedAt = now;
    if (input.displayName !== undefined) existing.displayName = input.displayName;
    persistList(list);
    notify();
    return existing;
  }
  const account: Account = {
    id: makeId(),
    brainUrl: trimmed,
    tenantId: input.tenantId,
    username: input.username,
    displayName: input.displayName,
    createdAt: now,
    lastUsedAt: now,
  };
  list.push(account);
  persistList(list);
  notify();
  return account;
}

/**
 * Mark {@code id} as the current account and bump its
 * {@code lastUsedAt}. Returns the updated account, or {@code null}
 * if no entry matches.
 */
export function setCurrent(id: string): Account | null {
  const list = listAccounts();
  const account = list.find((a) => a.id === id);
  if (account === undefined) return null;
  account.lastUsedAt = Date.now();
  persistList(list);
  getStorage().prefsStore.set(StorageKeys.accountsCurrent, id);
  notify();
  return account;
}

/**
 * Drop an account from the inventory and delete its per-account
 * token backups. Does not touch the flat / active-mirror keys —
 * the caller is responsible for invoking the active-mirror cleanup
 * (or {@link switchToAccount}) afterwards if the removed account
 * was the active one.
 */
export async function removeAccount(id: string): Promise<void> {
  const next = listAccounts().filter((a) => a.id !== id);
  persistList(next);
  if (currentAccountId() === id) {
    getStorage().prefsStore.remove(StorageKeys.accountsCurrent);
  }
  await clearTokensFor(id);
  notify();
}

/**
 * Resolve the most-recently-used account that isn't {@code excludeId}.
 * Used after sign-out to pick the next active account before falling
 * back to the login screen.
 */
export function fallbackAccountAfter(excludeId: string): Account | null {
  const list = listAccounts()
    .filter((a) => a.id !== excludeId)
    .sort((a, b) => b.lastUsedAt - a.lastUsedAt);
  return list[0] ?? null;
}

// ──────────────── token persistence (per-account namespaced) ────────────────

export async function saveTokensFor(
  accountId: string,
  access: string,
  refresh: string | undefined,
): Promise<void> {
  await SecureStore.setItemAsync(
    tokenKey(StorageKeys.authAccessToken, accountId),
    access,
  );
  if (refresh !== undefined) {
    await SecureStore.setItemAsync(
      tokenKey(StorageKeys.authRefreshToken, accountId),
      refresh,
    );
  }
}

export async function loadTokensFor(
  accountId: string,
): Promise<{ access: string | null; refresh: string | null }> {
  const [access, refresh] = await Promise.all([
    SecureStore.getItemAsync(tokenKey(StorageKeys.authAccessToken, accountId)),
    SecureStore.getItemAsync(tokenKey(StorageKeys.authRefreshToken, accountId)),
  ]);
  return { access, refresh };
}

export async function clearTokensFor(accountId: string): Promise<void> {
  await Promise.all([
    SecureStore.deleteItemAsync(tokenKey(StorageKeys.authAccessToken, accountId)),
    SecureStore.deleteItemAsync(tokenKey(StorageKeys.authRefreshToken, accountId)),
  ]);
}

// ──────────────── migration from the pre-Phase-B flat schema ────────────────

/**
 * Idempotent one-shot migration. If the flat identity keys hold an
 * account that hasn't been registered in the multi-account list, add
 * it as the first entry and mark it current. Safe to call on every
 * boot — re-runs are no-ops.
 *
 * <p>Runs after {@code preloadStorage} (so flat keys are readable
 * synchronously) but before any UI mounts.
 */
export async function migrateFromFlat(): Promise<void> {
  if (currentAccountId() !== null) {
    // Already migrated, or a fresh Phase-B login already happened.
    return;
  }
  const store = getStorage();
  const tenant = store.prefsStore.get(StorageKeys.identityTenantId);
  const username = store.prefsStore.get(StorageKeys.identityUsername);
  const brainUrl = store.prefsStore.get(StorageKeys.identityBrainUrl);
  if (tenant === null || username === null || brainUrl === null) {
    // Not signed in yet — nothing to migrate.
    return;
  }
  const account = upsertAccount({
    brainUrl,
    tenantId: tenant,
    username,
  });
  setCurrent(account.id);
  // Copy the flat-mirror tokens into the per-account backup so
  // a future switch-back restores them.
  const access = store.secureStore.get(StorageKeys.authAccessToken);
  const refresh = store.secureStore.get(StorageKeys.authRefreshToken);
  if (access !== null) {
    await saveTokensFor(account.id, access, refresh ?? undefined);
  }
}

// ──────────────── helpers ────────────────

function persistList(list: Account[]): void {
  getStorage().prefsStore.set(StorageKeys.accountsList, JSON.stringify(list));
}

function tokenKey(base: string, accountId: string): string {
  return `${base}.${accountId}`;
}

function trimUrl(url: string): string {
  return url.trim().replace(/\/+$/, '');
}

function isAccount(a: unknown): a is Account {
  if (typeof a !== 'object' || a === null) return false;
  const r = a as Record<string, unknown>;
  return (
    typeof r.id === 'string'
    && typeof r.brainUrl === 'string'
    && typeof r.tenantId === 'string'
    && typeof r.username === 'string'
    && typeof r.createdAt === 'number'
    && typeof r.lastUsedAt === 'number'
  );
}

function makeId(): string {
  const c = globalThis.crypto;
  if (c !== undefined && typeof c.randomUUID === 'function') {
    return c.randomUUID();
  }
  // Non-cryptographic fallback. Account ids are not security-sensitive;
  // they only need to be unique within this device's storage.
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** Compose the displayed name for an account. */
export function describeAccount(account: Account): string {
  if (account.displayName !== undefined && account.displayName.length > 0) {
    return account.displayName;
  }
  return `${account.username}@${account.tenantId}`;
}
