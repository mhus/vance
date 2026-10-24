// The signed-in user as the Web-UI knows it — one answer of
// `GET /brain/{tenant}/me`: identity, tenant role (and own project role),
// UI settings (`webui.*`, `chat.language`, display timezone) and the status
// hints (LLM power, budget, storage quota).
//
// Replaces the settings snapshot that used to ride in the `vance_data`
// cookie. The cookie now only carries authentication facts; everything that
// can change while it lives is read here and re-validated on every page load.
//
// Stale-while-revalidate: the last answer is kept in the prefsStore so a page
// renders with the user's theme, language and role-gated tiles right away,
// then the fresh answer replaces it and listeners (theme, locale) re-apply
// what changed. The snapshot is scoped to the identity in the data cookie — a
// different account never sees another account's settings.
//
// Display information only. The server enforces every request on its own.

import { shallowRef, type ShallowRef } from 'vue';
import type { MeDto } from '@vance/generated';
import { brainFetch, getStorage, StorageKeys } from '@vance/shared';
import { getSessionData } from './webUiSession';

interface Snapshot {
  tenantId: string;
  username: string;
  me: MeDto;
}

type MeListener = (next: MeDto | null, previous: MeDto | null) => void;

const meRef = shallowRef<MeDto | null>(null);
const listeners = new Set<MeListener>();
const inFlight = new Map<string, Promise<MeDto | null>>();
let hydrated = false;

/** Fill the ref from the snapshot once, lazily — never at module load. */
function hydrate(): void {
  if (hydrated) return;
  hydrated = true;
  meRef.value = readSnapshot();
}

function readSnapshot(): MeDto | null {
  const session = getSessionData();
  if (!session) return null;
  try {
    const raw = getStorage().prefsStore.get(StorageKeys.meSnapshot);
    if (!raw) return null;
    const snapshot = JSON.parse(raw) as Snapshot;
    if (snapshot.tenantId !== session.tenantId || snapshot.username !== session.username) {
      return null;
    }
    return snapshot.me ?? null;
  } catch {
    return null;
  }
}

function writeSnapshot(me: MeDto): void {
  try {
    const snapshot: Snapshot = { tenantId: me.tenantId, username: me.username, me };
    getStorage().prefsStore.set(StorageKeys.meSnapshot, JSON.stringify(snapshot));
  } catch {
    // Storage blocked (private mode, quota) — the in-memory answer still holds.
  }
}

/** The reactive answer — `null` until known. */
export function meState(): ShallowRef<MeDto | null> {
  hydrate();
  return meRef;
}

/** The current answer, synchronously — the snapshot until the fresh one is in. */
export function getMe(): MeDto | null {
  hydrate();
  return meRef.value;
}

/** One UI setting of the current user; `null` when unset or unknown. */
export function meSetting(key: string): string | null {
  const value = getMe()?.settings?.[key];
  return value != null && value.length > 0 ? value : null;
}

/**
 * Fetch the answer. With `project`, the own project role and the project's
 * budget come along. Resolves to `null` when the call fails; the previous
 * answer then stays in place.
 */
export function loadMe(project?: string | null): Promise<MeDto | null> {
  hydrate();
  const query = project && project.trim().length > 0 ? `?project=${encodeURIComponent(project)}` : '';
  // Boot and the first view of a page ask at the same moment — one request.
  const pending = inFlight.get(query);
  if (pending) return pending;
  const request = fetchMe(query).finally(() => inFlight.delete(query));
  inFlight.set(query, request);
  return request;
}

async function fetchMe(query: string): Promise<MeDto | null> {
  let next: MeDto;
  try {
    next = await brainFetch<MeDto>('GET', `me${query}`);
  } catch {
    return null;
  }
  const previous = meRef.value;
  meRef.value = next;
  writeSnapshot(next);
  for (const listener of listeners) {
    try {
      listener(next, previous);
    } catch {
      // One broken listener must not keep the others from the update.
    }
  }
  return next;
}

/** Called after every fresh answer with the previous one. Returns an unsubscribe. */
export function onMeChanged(listener: MeListener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** Forget the answer and its snapshot — on logout. */
export function clearMe(): void {
  meRef.value = null;
  try {
    getStorage().prefsStore.remove(StorageKeys.meSnapshot);
  } catch {
    // nothing to clean up then
  }
}
