import { StorageKeys } from '../persistence/keys';

/**
 * Tenant + username pair the login form pre-fills when the user
 * checked "Remember user" on a previous successful sign-in. Pure
 * convenience — never carries credentials, never carries a token.
 *
 * <p>Stored as JSON under {@link StorageKeys.rememberedLogin} so we
 * can add additional hints later (last-used login mode, default
 * landing editor) without bumping the key.
 */
export interface RememberedLogin {
  tenant: string;
  username: string;
}

export function getRememberedLogin(): RememberedLogin | null {
  const raw = localStorage.getItem(StorageKeys.rememberedLogin);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as Partial<RememberedLogin>;
    if (typeof parsed?.tenant !== 'string' || typeof parsed?.username !== 'string') {
      return null;
    }
    return { tenant: parsed.tenant, username: parsed.username };
  } catch {
    return null;
  }
}

export function setRememberedLogin(value: RememberedLogin): void {
  localStorage.setItem(StorageKeys.rememberedLogin, JSON.stringify(value));
}

export function clearRememberedLogin(): void {
  localStorage.removeItem(StorageKeys.rememberedLogin);
}
