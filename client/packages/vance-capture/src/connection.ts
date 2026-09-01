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
 * The match pattern the extension has to be allowed to reach.
 *
 * <p><b>Deliberately not the origin.</b> A match pattern is
 * `<scheme>://<host>/<path>` and <b>cannot express a port</b> — while
 * `URL.origin` includes one. Passing `http://localhost:9901/*` makes the
 * pattern invalid, and Safari does not answer "no": it <em>throws</em>, which
 * in a click handler surfaces as an unhandled rejection and a button that
 * appears dead.
 *
 * <p>Consequence, and it is honest to name it: host access is granted per
 * <em>host</em>, not per origin. Allowing `localhost` allows it on every port.
 * The pattern language has no way to say otherwise, so the alternative to this
 * is not a narrower grant — it is no grant at all.
 */
export function originOf(blob: ConnectionBlob): string | null {
  try {
    const url = new URL(blob.brainUrl);
    return `${url.protocol}//${url.hostname}/*`;
  } catch {
    return null;
  }
}

/**
 * Whether the browser has already been told this extension may reach the brain.
 *
 * <p>Never throws. The permissions API rejects a malformed pattern by throwing,
 * and this is called from page setup where an exception means the rest of the
 * page never renders — a far worse outcome than answering "not granted".
 */
export async function hasHostAccess(blob: ConnectionBlob): Promise<boolean> {
  const origin = originOf(blob);
  if (!origin) return false;
  try {
    return await api.permissions.contains({ origins: [origin] });
  } catch {
    return false;
  }
}

/**
 * Ask for it. Must be called from a user gesture — Chrome silently refuses
 * otherwise, which is why this only ever runs from a click handler.
 */
export async function requestHostAccess(blob: ConnectionBlob): Promise<boolean> {
  const origin = originOf(blob);
  if (!origin) return false;
  try {
    return await api.permissions.request({ origins: [origin] });
  } catch (e) {
    // A rejected pattern throws here rather than returning false. Turning it
    // into a value is what lets the caller say something instead of dying
    // silently inside a click handler.
    throw new Error(`The browser refused the access request for ${origin}: `
      + `${(e as Error).message}`);
  }
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

/**
 * A web-UI URL that opens one document by path.
 *
 * <p><b>The token has nothing to do with this.</b> Opening a URL uses the
 * person's own browser session and their login cookies — the extension's
 * credential covers only what the extension fetches itself. So this needs no
 * capability, no profile and no permission; it is a link.
 *
 * <p>`path` is Cortex's one-shot handoff parameter: it resolves a path to a
 * document id and opens that tab. Exactly right for a caller that has a path
 * and no ids, which is every outside tool.
 *
 * <p>The one thing it does assume is that the person is logged into the web UI
 * in this browser. If they are not, they land on the login page — which is the
 * correct outcome, just worth knowing before calling it a bug.
 */
export function cortexUrlFor(blob: ConnectionBlob, path: string): string {
  const query = new URLSearchParams({ project: blob.projectId, path });
  return `${blob.brainUrl}/cortex?${query}`;
}

/** The links app's own manifest — the document that *is* the app. */
export function linksAppUrl(blob: ConnectionBlob): string | null {
  const folder = (blob.target ?? '').replace(/^\/+|\/+$/g, '');
  if (!folder) return null;
  return cortexUrlFor(blob, `${folder}/_app.yaml`);
}

/** Days until the token expires, or `null` when it carries no expiry. */
export function daysLeft(blob: ConnectionBlob): number | null {
  if (!blob.expiresAt) return null;
  return Math.floor((blob.expiresAt - Date.now()) / 86_400_000);
}
