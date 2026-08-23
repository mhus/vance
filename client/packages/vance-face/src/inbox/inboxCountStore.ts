import { computed, ref, type ComputedRef, type Ref } from 'vue';
import type { InboxCountResponse } from '@vance/generated';
import { brainFetch, getTenantId } from '@vance/shared';

/**
 * The numbers behind the topbar inbox badge, so "does anything want me?" is
 * answerable without opening the inbox editor.
 *
 * <p>Two groups, and they are not interchangeable. {@link inboxUnread} is the
 * alarm — threads with something unopened — and it is what the badge shows. A
 * decision that was read and deliberately held back does not appear in it: a
 * badge that cannot reach zero without deciding trains people to dismiss.
 * {@link inboxPending} is the stock of open matters and lives in the tooltip.
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

const unread: Ref<number> = ref(0);
const unreadRequiresAction: Ref<number> = ref(0);
const pending: Ref<number> = ref(0);
const loaded: Ref<boolean> = ref(false);

/** In-flight refresh, so mount + visibilitychange don't fire twice. */
let inFlight: Promise<void> | null = null;

/** Threads with something unread for the current user — the badge's number. */
export const inboxUnread: ComputedRef<number> = computed(() => unread.value);

/**
 * Subset of {@link inboxUnread} that is an open ask assigned to this user.
 * Drives the badge's colour — a shared note is worth showing, not worth
 * alarming.
 *
 * <p>From the same population as {@link inboxUnread} on purpose: colouring on
 * all open asks would paint the badge red because something is open somewhere,
 * even when every unread thread is a harmless output.
 */
export const inboxUnreadRequiresAction: ComputedRef<number> =
  computed(() => unreadRequiresAction.value);

/** Open items assigned to the current user — the stock, shown in the tooltip. */
export const inboxPending: ComputedRef<number> = computed(() => pending.value);

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
      unread.value = data.unread ?? 0;
      unreadRequiresAction.value = data.unreadRequiresAction ?? 0;
      pending.value = data.pending ?? 0;
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
