import { Preferences } from '@capacitor/preferences';

/**
 * App-lock state. The lock gate sits in front of every authenticated
 * route — the wrapper boots into `/lock/setup` on first launch
 * (after install) and `/lock/unlock` on every cold start thereafter.
 * Biometric (Face-ID / Touch-ID) can bypass the PIN step but is
 * optional; the PIN itself is the must-have credential.
 *
 * **Persistence**:
 * - The PIN-hash + salt live in {@code @capacitor/preferences} →
 *   iOS `UserDefaults`. UserDefaults is wiped when the user removes
 *   the app, so a re-install starts at the "setup PIN" screen
 *   without manual reset.
 * - The "currently unlocked" flag is **in-memory only**. Backgrounded
 *   apps stay unlocked (matches typical mobile-banking UX); a hard
 *   kill from the multitasker re-locks on next launch.
 *
 * **Hashing**: SHA-256 over `salt + pin`, hex-encoded. Salt is 16
 * random bytes per install (re-rolled with the PIN). This is overkill
 * for a 4-6 digit local PIN but harmless and matches what app-stores
 * expect from a "secure" PIN-lock.
 */

const KEY_PIN_HASH = 'vance.lock.pinHash';
const KEY_PIN_SALT = 'vance.lock.pinSalt';
const KEY_BIOMETRIC_ENABLED = 'vance.lock.biometricEnabled';

/** PIN length constraints — picked here, used by the UI to enable
 *  the Submit button. Keep narrow so the user understands the
 *  affordance ("4 to 6 digits"). */
export const MIN_PIN_LENGTH = 4;
export const MAX_PIN_LENGTH = 6;

let unlockedInMemory = false;

export function isUnlocked(): boolean {
  return unlockedInMemory;
}

/** Hard-lock — typically called when the user explicitly signs out
 *  via a "Lock now" affordance. Cold-start re-locking happens for
 *  free because the in-memory flag doesn't persist across processes. */
export function lock(): void {
  unlockedInMemory = false;
}

export async function isPinConfigured(): Promise<boolean> {
  const { value } = await Preferences.get({ key: KEY_PIN_HASH });
  return value !== null && value.length > 0;
}

export async function setPin(pin: string): Promise<void> {
  if (pin.length < MIN_PIN_LENGTH || pin.length > MAX_PIN_LENGTH) {
    throw new Error(`PIN must be ${MIN_PIN_LENGTH}–${MAX_PIN_LENGTH} digits`);
  }
  if (!/^\d+$/.test(pin)) {
    throw new Error('PIN must be numeric');
  }
  const salt = generateSaltHex();
  const hash = await hashPin(pin, salt);
  await Preferences.set({ key: KEY_PIN_SALT, value: salt });
  await Preferences.set({ key: KEY_PIN_HASH, value: hash });
  unlockedInMemory = true;
}

export async function verifyPin(pin: string): Promise<boolean> {
  const [{ value: hash }, { value: salt }] = await Promise.all([
    Preferences.get({ key: KEY_PIN_HASH }),
    Preferences.get({ key: KEY_PIN_SALT }),
  ]);
  if (hash === null || salt === null) return false;
  const candidate = await hashPin(pin, salt);
  const ok = constantTimeEqual(candidate, hash);
  if (ok) unlockedInMemory = true;
  return ok;
}

/** Mark the app unlocked without a PIN check — used by the biometric
 *  pathway. UI MUST verify biometric success before calling this. */
export function markUnlockedByBiometric(): void {
  unlockedInMemory = true;
}

export async function isBiometricEnabled(): Promise<boolean> {
  const { value } = await Preferences.get({ key: KEY_BIOMETRIC_ENABLED });
  return value === 'true';
}

export async function setBiometricEnabled(enabled: boolean): Promise<void> {
  await Preferences.set({
    key: KEY_BIOMETRIC_ENABLED,
    value: enabled ? 'true' : 'false',
  });
}

/** 16 random bytes, hex-encoded. */
function generateSaltHex(): string {
  const buf = new Uint8Array(16);
  crypto.getRandomValues(buf);
  return Array.from(buf)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

async function hashPin(pin: string, saltHex: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(`${saltHex}:${pin}`);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let mismatch = 0;
  for (let i = 0; i < a.length; i++) {
    mismatch |= a.charCodeAt(i) ^ b.charCodeAt(i);
  }
  return mismatch === 0;
}
