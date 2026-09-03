// The dedicated subpath, not the `@vance/shared` barrel. The barrel re-exports
// the whole client — REST, WebSocket, js-yaml — and pulling it in for one
// dependency-free codec put 50 kB of unreachable brain client into a browser
// extension. The subpath exists for exactly this: one format, two ends, no
// second copy and no cargo.
import { api } from './browserApi';
import { type ConnectionBlob, decodeConnectionBlob } from '@vance/shared/integration-connection';

/**
 * The stored connections — everything the extension knows about its brains.
 *
 * <p><b>`storage.local`, never `storage.sync`.</b> This holds credentials.
 * Sync would carry them to every machine signed into the browser profile,
 * including ones the person never meant to give capture access to, and they
 * would survive on machines they later stop using. A token that travels
 * further than the human intended is the exact failure the confinement was
 * built to bound.
 *
 * <p><b>A list, and the unit of the list is the connection — not the tenant.</b>
 * A blob names a brain, a tenant, a project <em>and</em> a target folder, so
 * two link lists in the same project of the same tenant are two destinations,
 * and that is a routine case (work and private as two folders). Keying the
 * plural on the tenant would have missed it on the first day.
 */
const KEY = 'connections';

/**
 * Which one the popup acts on. Stored rather than derived so the choice
 * survives closing the popup, and so it is the same in every window.
 */
const ACTIVE_KEY = 'activeConnection';

/** What single-connection builds wrote. Read once, on the way to {@link KEY}. */
const LEGACY_KEY = 'connection';
const LEGACY_FOLDER_KEY = 'grabFolder';

export type { ConnectionBlob };

/**
 * One destination as the extension holds it: what the server handed out, plus
 * the two things that are the person's own.
 */
export interface StoredConnection {
  /**
   * The credential and what it is pinned to, exactly as it was pasted.
   *
   * <p>Never edited. It is the server's statement; the fields around it are
   * where local preferences go.
   */
  blob: ConnectionBlob;
  /**
   * Name in the picker. Empty means "derive one" — see {@link connectionLabel}.
   *
   * <p>Seeded from the link list's own title, which the settings page already
   * gets back from the round trip that verifies the token, so it costs no extra
   * call.
   */
  label: string;
  /**
   * Where inside <em>this</em> target's project "Save page" writes.
   *
   * <p>Per connection rather than one setting for the extension: the folder is
   * a property of the destination, and a single value would send grabs for a
   * second project into a path that means nothing there. Not inside the blob
   * for the same reason the blob is never edited — baking it into a credential
   * would mean re-minting to change it. Empty means "let the server decide",
   * which it does.
   */
  grabFolder: string;
}

/**
 * What makes two records the same destination.
 *
 * <p><b>The token is deliberately not part of it.</b> A token is a credential
 * with an expiry; pasting a freshly minted one for a target that is already
 * configured is a renewal, and it has to replace that record rather than sit
 * beside it. Without this rule the list grows a twin on every renewal and the
 * picker offers three entries of which one works.
 *
 * <p>JSON of the four fields rather than a joined string: a folder path can
 * contain very nearly anything, and a separator that a value can also contain
 * is how two distinct destinations quietly become one key.
 */
export function connectionKey(blob: ConnectionBlob): string {
  return JSON.stringify([
    blob.brainUrl.replace(/\/+$/, ''),
    blob.tenant,
    blob.projectId,
    normalizeFolder(blob.target),
  ]);
}

/** A target folder without its decorative slashes. */
export function normalizeFolder(target?: string | null): string {
  return (target ?? '').replace(/^\/+|\/+$/g, '');
}

/**
 * What to call it.
 *
 * <p>Falls back through folder to project, because a picker entry has to say
 * something and every blob has a project. Never the brain URL: with two lists
 * on one brain that reads as one duplicated entry.
 */
export function connectionLabel(record: StoredConnection): string {
  return record.label.trim()
    || normalizeFolder(record.blob.target)
    || record.blob.projectId;
}

function isStored(value: unknown): value is StoredConnection {
  const record = value as StoredConnection | null;
  const blob = record?.blob as ConnectionBlob | undefined;
  return !!blob && typeof blob.brainUrl === 'string' && typeof blob.tenant === 'string'
    && typeof blob.projectId === 'string' && typeof blob.token === 'string';
}

/**
 * Every configured destination, oldest first.
 *
 * <p>Carries the migration from the single-connection layout, because this is
 * the one function every caller goes through — putting it anywhere else would
 * mean a page that forgot the call shows an installed extension as
 * unconfigured, which looks exactly like a lost token.
 */
export async function loadConnections(): Promise<StoredConnection[]> {
  const stored = await api.storage.local.get([KEY, LEGACY_KEY, LEGACY_FOLDER_KEY]);
  const list = stored[KEY];
  if (Array.isArray(list)) return list.filter(isStored);

  const legacy = stored[LEGACY_KEY];
  if (!legacy || typeof legacy !== 'object') return [];
  const migrated: StoredConnection[] = [{
    blob: legacy as ConnectionBlob,
    // No label: the one connection had no name to carry, and deriving one here
    // would freeze today's fallback into stored data.
    label: '',
    grabFolder: typeof stored[LEGACY_FOLDER_KEY] === 'string' ? stored[LEGACY_FOLDER_KEY] : '',
  }];
  // New shape first, old keys after. The other order has a window in which a
  // second page finds neither and reports an unconfigured extension.
  await api.storage.local.set({
    [KEY]: migrated,
    [ACTIVE_KEY]: connectionKey(migrated[0].blob),
  });
  await api.storage.local.remove([LEGACY_KEY, LEGACY_FOLDER_KEY]);
  return migrated;
}

/**
 * The one the popup acts on, or `null` when nothing is configured.
 *
 * <p>A stored key that names no record — forgotten in another window — falls
 * back to the first rather than to "unconfigured": there is a usable
 * destination, and refusing to pick it would make a stale pointer look like a
 * lost setup.
 */
export async function loadActive(): Promise<StoredConnection | null> {
  const list = await loadConnections();
  if (list.length === 0) return null;
  const stored = await api.storage.local.get(ACTIVE_KEY);
  const key = stored[ACTIVE_KEY];
  const found = typeof key === 'string'
    ? list.find((c) => connectionKey(c.blob) === key)
    : undefined;
  return found ?? list[0];
}

export async function setActive(key: string): Promise<void> {
  await api.storage.local.set({ [ACTIVE_KEY]: key });
}

/**
 * Store a pasted connection — replacing the record for the same destination,
 * and making it the active one.
 *
 * <p>The label and the grab folder of an existing record survive: they are the
 * person's, and a renewal that reset the name in the picker and sent the next
 * grab somewhere else would be a renewal with side effects.
 */
export async function upsertConnection(
  blob: ConnectionBlob,
  seed: { label?: string } = {},
): Promise<StoredConnection> {
  const list = await loadConnections();
  const key = connectionKey(blob);
  const at = list.findIndex((c) => connectionKey(c.blob) === key);
  const previous = at >= 0 ? list[at] : null;
  const record: StoredConnection = {
    blob,
    label: previous?.label.trim() || (seed.label ?? '').trim(),
    grabFolder: previous?.grabFolder ?? '',
  };
  const next = list.slice();
  if (at >= 0) next[at] = record; else next.push(record);
  await api.storage.local.set({ [KEY]: next, [ACTIVE_KEY]: key });
  return record;
}

/** Change the two local fields. Absent keys are left alone. */
export async function updateConnection(
  key: string,
  patch: { label?: string; grabFolder?: string },
): Promise<void> {
  const list = await loadConnections();
  const at = list.findIndex((c) => connectionKey(c.blob) === key);
  if (at < 0) return;
  const next = list.slice();
  next[at] = {
    ...list[at],
    label: patch.label === undefined ? list[at].label : patch.label.trim(),
    grabFolder: patch.grabFolder === undefined ? list[at].grabFolder : patch.grabFolder.trim(),
  };
  await api.storage.local.set({ [KEY]: next });
}

/**
 * Forget one destination. Returns what is left.
 *
 * <p>Moves the active pointer when it named this one, rather than leaving it
 * dangling for {@link loadActive} to paper over — the fallback there is for a
 * race, not for a state we could have written correctly.
 */
export async function removeConnection(key: string): Promise<StoredConnection[]> {
  const list = await loadConnections();
  const remaining = list.filter((c) => connectionKey(c.blob) !== key);
  const patch: Record<string, unknown> = { [KEY]: remaining };
  const stored = await api.storage.local.get(ACTIVE_KEY);
  if (stored[ACTIVE_KEY] === key && remaining.length > 0) {
    patch[ACTIVE_KEY] = connectionKey(remaining[0].blob);
  }
  await api.storage.local.set(patch);
  if (remaining.length === 0) await api.storage.local.remove(ACTIVE_KEY);
  return remaining;
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
 * Hand back host access after forgetting a connection — but only if nothing
 * left needs it.
 *
 * <p>The check is the whole point. A granted host is a standing capability, so
 * leaving it behind after the last destination on that brain is gone means the
 * extension keeps a reach it has no use for. But two destinations routinely
 * share a brain, and revoking on the first "Forget" would take the access away
 * from a connection that is still configured — a working setup broken by
 * tidying up an unrelated one.
 *
 * <p>Best effort: a browser that refuses is left as it was. This runs after the
 * record is already gone, and turning a failed cleanup into a visible error
 * would report the removal as failed when it succeeded.
 */
export async function releaseHostAccess(
  blob: ConnectionBlob,
  remaining: StoredConnection[],
): Promise<void> {
  const origin = originOf(blob);
  if (!origin) return;
  if (remaining.some((c) => originOf(c.blob) === origin)) return;
  try {
    await api.permissions.remove({ origins: [origin] });
  } catch {
    // Nothing to say and nothing to do.
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
  const folder = normalizeFolder(blob.target);
  if (!folder) return null;
  return cortexUrlFor(blob, `${folder}/_app.yaml`);
}

/** Days until the token expires, or `null` when it carries no expiry. */
export function daysLeft(blob: ConnectionBlob): number | null {
  if (!blob.expiresAt) return null;
  return Math.floor((blob.expiresAt - Date.now()) / 86_400_000);
}
