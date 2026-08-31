import type { ConnectionBlob } from './connection';

/**
 * The three capture routes, and nothing else.
 *
 * <p>They are the whole surface the token opens (`links-capture`), so this file
 * being short is not an omission — asking for anything not here would come back
 * 401 from the access filter, not 403 from the app. A caller that finds itself
 * wanting `/scan` wants a different profile.
 *
 * <p><b>No CORS problem, and it is worth knowing why.</b> These calls run in an
 * extension page (the popup, the options tab), which carries the extension's
 * own origin and its granted host permissions. The brain sets no CORS headers;
 * the same fetch from a content script — the page's origin — would be blocked.
 */

/** What the brain answered when it did not answer with data. */
export class CaptureError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = 'CaptureError';
  }
}

export interface GroupsView {
  folder: string;
  title?: string | null;
  groups: string[];
}

export interface LookupView {
  found: boolean;
  url: string;
  title?: string | null;
  group?: string | null;
  tags: string[];
  note?: string | null;
  addedAt?: string | null;
  viewedAt?: string | null;
}

export interface CaptureView {
  added: boolean;
  url: string;
  title?: string | null;
  group?: string | null;
  viewed: boolean;
}

function endpoint(conn: ConnectionBlob, path: string, extra: Record<string, string> = {}): string {
  const query = new URLSearchParams({
    projectId: conn.projectId,
    folder: conn.target ?? '',
    ...extra,
  });
  return `${conn.brainUrl}/brain/${encodeURIComponent(conn.tenant)}/addon/links/${path}?${query}`;
}

async function call<T>(
  conn: ConnectionBlob,
  method: 'GET' | 'POST',
  url: string,
  body?: unknown,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, {
      method,
      headers: {
        Authorization: `Bearer ${conn.token}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    // A network-level failure here is usually one of two things, and the
    // person can act on both: the brain is not reachable, or the browser has
    // not been granted access to that host. Status 0 marks it as "never got an
    // answer" so the caller can say so instead of inventing an HTTP reason.
    throw new CaptureError(0, `Could not reach ${conn.brainUrl} (${(e as Error).message})`);
  }
  if (!response.ok) {
    throw new CaptureError(response.status, await describe(response));
  }
  return response.json() as Promise<T>;
}

async function describe(response: Response): Promise<string> {
  if (response.status === 401) {
    return 'The token was rejected — it may have been revoked or expired. '
      + 'Create a new one in the link list and paste it again.';
  }
  if (response.status === 403) {
    return 'The account behind this token is not allowed to write to that project.';
  }
  if (response.status === 404) {
    return 'No link list at that folder — check the connection string.';
  }
  const text = await response.text().catch(() => '');
  return text || `${response.status} ${response.statusText}`;
}

/** Group headings, for the "file it under…" picker. */
export async function listGroups(conn: ConnectionBlob): Promise<GroupsView> {
  return call<GroupsView>(conn, 'GET', endpoint(conn, 'groups'));
}

/** Whether this one page is already in the list. */
export async function lookup(conn: ConnectionBlob, url: string): Promise<LookupView> {
  return call<LookupView>(conn, 'GET', endpoint(conn, 'entry/lookup', { url }));
}

/** Save. Idempotent on the URL — `added: false` means it was already there. */
export async function capture(
  conn: ConnectionBlob,
  entry: { url: string; title?: string; group?: string; note?: string; tags?: string[] },
): Promise<CaptureView> {
  return call<CaptureView>(conn, 'POST', endpoint(conn, 'capture'), entry);
}
