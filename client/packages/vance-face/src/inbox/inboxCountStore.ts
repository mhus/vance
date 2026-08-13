import { computed, ref, type ComputedRef, type Ref } from 'vue';
import type { InboxCountResponse } from '@vance/generated';
import { brainFetch, getTenantId } from '@vance/shared';

/**
 * How many inbox items are still pending for the signed-in user — the
 * number behind the topbar inbox badge, so "do I have new inbox
 * messages?" is answerable without opening the inbox editor.
 *
 * <p>REST-pulled, not pushed: web-ui v1 keeps live updates inside the
 * chat editor (see specification/web-ui.md §3-§4), and the brain's
 * {@code inbox-item-added} push only reaches a socket bound to a chat
 * session. The badge therefore refreshes when a page mounts, when the
 * tab regains focus, and after every inbox mutation the user performs
 * (see {@code useInbox}).
 *
 * <p>Module-level reactive singleton rather than a Pinia store, for the
 * same reason as {@code processCountsStore}: the badge lives in
 * EditorTopbar, which every MPA entry inherits, and not every entry
 * registers Pinia.
 */

const pending: Ref<number> = ref(0);
const requiresAction: Ref<number> = ref(0);
const loaded: Ref<boolean> = ref(false);

/** In-flight refresh, so mount + visibilitychange don't fire twice. */
let inFlight: Promise<void> | null = null;

/** Pending items assigned to the current user (answers + pure outputs). */
export const inboxPending: ComputedRef<number> = computed(() => pending.value);

/**
 * Subset of {@link inboxPending} a process actually waits on. Drives the
 * badge's colour — a shared note is worth showing, not worth alarming.
 */
export const inboxRequiresAction: ComputedRef<number> = computed(() => requiresAction.value);

/**
 * Whether a count has arrived at least once. The badge stays hidden
 * until then, so a failed or not-yet-finished request never renders a
 * misleading zero.
 */
export const inboxCountLoaded: ComputedRef<boolean> = computed(() => loaded.value);

/**
 * Pulls the current counts. Cheap by design — the endpoint counts in
 * Mongo and returns two numbers, no item bodies.
 *
 * <p>Never rejects: the badge is decoration, and a hiccup on the count
 * endpoint must not surface as an unhandled rejection in an editor that
 * has nothing to do with the inbox. On failure the previous numbers stay
 * put and the next refresh tries again.
 */
export function refreshInboxCount(): Promise<void> {
  if (inFlight) return inFlight;
  if (!getTenantId()) return Promise.resolve();
  inFlight = brainFetch<InboxCountResponse>('GET', 'inbox/count')
    .then((data) => {
      pending.value = data.pending ?? 0;
      requiresAction.value = data.requiresAction ?? 0;
      loaded.value = true;
    })
    .catch(() => {
      // Keep the last known numbers; the badge is not worth an error toast.
    })
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}
