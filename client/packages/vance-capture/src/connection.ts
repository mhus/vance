// The dedicated subpath, not the `@vance/shared` barrel. The barrel re-exports
// the whole client — REST, WebSocket, js-yaml — and pulling it in for one
// dependency-free codec put 50 kB of unreachable brain client into a browser
// extension. The subpath exists for exactly this: one format, two ends, no
// second copy and no cargo.
import { api } from './browserApi';
import { type ConnectionBlob, decodeConnectionBlob } from '@vance/shared/integration-connection';

/**
 * The stored connection — everything the extension knows about its brain.
 *
 * <p><b>`storage.local`, never `storage.sync`.</b> This holds a credential.
 * Sync would carry it to every machine signed into the browser profile,
 * including ones the person never meant to give capture access to, and it would
 * survive on machines they later stop using. A token that travels further than
 * the human intended is the exact failure the confinement was built to bound.
 */
const KEY = 'connection';

/**
 * Where grabs land inside the project.
 *
 * <p>The extension's own setting rather than part of the connection string,
 * because the string carries what the *server* handed out — the token and what
 * it is pinned to. Which folder a person wants their saved pages in is a local
 * preference, and baking it into a credential would mean re-minting to change
 * it. Empty means "let the server decide", which it does.
 */
const GRAB_FOLDER_KEY = 'grabFolder';

export type { ConnectionBlob };

export async function loadConnection(): Promise<ConnectionBlob | null> {
  const stored = await api.storage.local.get(KEY);
  const value = stored[KEY];
  return value && typeof value === 'object' ? (value as ConnectionBlob) : null;
}

export async function saveConnection(blob: ConnectionBlob): Promise<void> {
  await api.storage.local.set({ [KEY]: blob });
}

export async function clearConnection(): Promise<void> {
  await api.storage.local.remove(KEY);
}

export async function loadGrabFolder(): Promise<string> {
  const stored = await api.storage.local.get(GRAB_FOLDER_KEY);
  const value = stored[GRAB_FOLDER_KEY];
  return typeof value === 'string' ? value : '';
}

export async function saveGrabFolder(folder: string): Promise<void> {
  await api.storage.local.set({ [GRAB_FOLDER_KEY]: folder.trim() });
}

/** Parse a pasted string. `null` for anything that is not a usable one. */
export function parseConnection(text: string): ConnectionBlob | null {
  return decodeConnectionBlob(text);
}

/**
 * The origin the extension has to be allowed to reach.
 *
 * <p>Extracted from the URL rather than stored separately: the two would
 * eventually disagree, and the one that decides whether a request goes out is
 * this one.
 */
export function originOf(blob: ConnectionBlob): string | null {
  try {
    return `${new URL(blob.brainUrl).origin}/*`;
  } catch {
    return null;
  }
}

/** Whether the browser has already been told this extension may reach the brain. */
export async function hasHostAccess(blob: ConnectionBlob): Promise<boolean> {
  const origin = originOf(blob);
  if (!origin) return false;
  return api.permissions.contains({ origins: [origin] });
}

/**
 * Ask for it. Must be called from a user gesture — Chrome silently refuses
 * otherwise, which is why this only ever runs from a click handler.
 */
export async function requestHostAccess(blob: ConnectionBlob): Promise<boolean> {
  const origin = originOf(blob);
  if (!origin) return false;
  return api.permissions.request({ origins: [origin] });
}

/**
 * Whether the stored token is known *not* to carry a capability.
 *
 * <p>Advisory, and only ever used to produce a better message: the server
 * re-reads the profiles from the signed claim and is the authority. What this
 * buys is that "your token cannot do this" is said before a tab is read and
 * uploaded for a guaranteed rejection — and said precisely, instead of as the
 * filter's bare 401.
 *
 * <p>Returns false when the blob names no profiles at all. That is a string
 * minted before capabilities became a list, and refusing on the strength of a
 * field that predates the question would be worse than trying.
 */
export function knownToLack(blob: ConnectionBlob, profile: string): boolean {
  const profiles = blob.profiles ?? [];
  return profiles.length > 0 && !profiles.includes(profile);
}

/** Days until the token expires, or `null` when it carries no expiry. */
export function daysLeft(blob: ConnectionBlob): number | null {
  if (!blob.expiresAt) return null;
  return Math.floor((blob.expiresAt - Date.now()) / 86_400_000);
}
