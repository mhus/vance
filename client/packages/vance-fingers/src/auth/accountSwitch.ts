import {
  StorageKeys,
  configurePlatform,
  getRestConfig,
  getStorage,
} from '@vance/shared';
import { queryClient } from '@/api/queryClient';
import {
  type Account,
  loadTokensFor,
  setCurrent,
} from './accountStore';

/**
 * Atomic account-switch primitive. Pulls together the four steps
 * that have to run as one unit:
 *
 * <ol>
 *   <li>Mark the target account as current in the inventory.</li>
 *   <li>Mirror its identity + tokens into the flat keys so existing
 *       {@code getTenantId() / getStorage().secureStore.get(...)}
 *       readers see the new account without any refactor.</li>
 *   <li>Rebind {@link configurePlatform} with the account's brain URL
 *       so the next REST/WS call talks to the right server.</li>
 *   <li>Wipe the React-Query cache — entries fetched against the
 *       previous account would be stale at best, cross-account
 *       leaks at worst.</li>
 * </ol>
 *
 * <p>The actual cold-restart effect (UI re-mount, fresh WS connect,
 * etc.) is driven by {@code App.tsx}'s
 * {@code <RootNavigator key={currentAccountId} />} — once
 * {@code currentAccountId} flips, React tears down the entire
 * subtree and remounts it. That happens via
 * {@code accountStore.subscribe} which fires from within
 * {@link setCurrent}.
 *
 * @returns the account that's now active, or {@code null} if
 *          {@code accountId} doesn't match any inventory entry.
 */
export async function switchToAccount(accountId: string): Promise<Account | null> {
  const account = setCurrent(accountId);
  if (account === null) return null;
  await mirrorActiveAccount(account);
  applyBrainUrl(account.brainUrl);
  queryClient.clear();
  return account;
}

/**
 * Update the flat-key active mirror so synchronous consumers
 * ({@code getTenantId()}, {@code getStorage().secureStore.get(...)})
 * read the target account's data. Tokens come from the per-account
 * SecureStore backup; identity goes through {@code prefsStore}.
 */
export async function mirrorActiveAccount(account: Account): Promise<void> {
  const store = getStorage();
  store.prefsStore.set(StorageKeys.identityTenantId, account.tenantId);
  store.prefsStore.set(StorageKeys.identityUsername, account.username);
  store.prefsStore.set(StorageKeys.identityBrainUrl, account.brainUrl);
  const { access, refresh } = await loadTokensFor(account.id);
  if (access !== null) {
    store.secureStore.set(StorageKeys.authAccessToken, access);
  } else {
    store.secureStore.remove(StorageKeys.authAccessToken);
  }
  if (refresh !== null) {
    store.secureStore.set(StorageKeys.authRefreshToken, refresh);
  } else {
    store.secureStore.remove(StorageKeys.authRefreshToken);
  }
  // Active session is per-account too — don't carry one account's
  // active session into another account's UI (would 404 the chat
  // session immediately).
  store.prefsStore.remove(StorageKeys.activeSessionId);
}

/**
 * Wipe the active mirror — flat identity + token keys go away.
 * Used after sign-out when no fallback account remains.
 */
export function clearActiveMirror(): void {
  const store = getStorage();
  store.secureStore.remove(StorageKeys.authAccessToken);
  store.secureStore.remove(StorageKeys.authRefreshToken);
  store.prefsStore.remove(StorageKeys.identityTenantId);
  store.prefsStore.remove(StorageKeys.identityUsername);
  // identityBrainUrl is intentionally NOT cleared — a logged-out user
  // who comes back to the login screen sees their last-used URL
  // pre-filled, which is the friendlier UX.
  store.prefsStore.remove(StorageKeys.activeSessionId);
}

function applyBrainUrl(baseUrl: string): void {
  const current = getRestConfig();
  if (current.baseUrl === baseUrl) return;
  configurePlatform({
    storage: getStorage(),
    rest: {
      ...current,
      baseUrl,
    },
  });
}
