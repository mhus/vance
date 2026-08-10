import { computed, ref, type ComputedRef, type Ref } from 'vue';
import type { ProcessCountsNotification } from '@vance/generated';

/**
 * How many think-processes of the bound session are running / waiting /
 * blocked — fed by the {@code process-counts} WebSocket notification and
 * rendered as a badge in the editor topbar.
 *
 * <p>Push-only: the server sends one frame at welcome/resume time and
 * afterwards whenever the numbers change (per-turn RUNNING↔IDLE flapping is
 * coalesced away server-side). There is no polling fallback and no REST pull.
 *
 * <p>Because the welcome push is unconditional — it reports zeros too, to
 * clear a stale badge — <em>having received a frame at all</em> is exactly the
 * statement "this editor has a bound session". {@link countsAvailable} builds
 * the badge's visibility on that, so the process list stays reachable even
 * while nothing but the chat is running.
 *
 * <p>Module-level reactive singleton rather than a Pinia store, for the same
 * reason as {@code notificationStore}: the badge lives inside EditorShell,
 * which every MPA entry-point inherits, and not every entry registers Pinia.
 *
 * <p>Requirement: planning/process-visibility.md §4.A
 */

export interface ProcessCounts {
  running: number;
  waiting: number;
  blocked: number;
  total: number;
}

const ZERO: ProcessCounts = { running: 0, waiting: 0, blocked: 0, total: 0 };

const state: Ref<ProcessCounts> = ref({ ...ZERO });

/** Whether a frame has arrived on the current connection. */
const received: Ref<boolean> = ref(false);

/** Current counts. Never null — an unbound editor reads zeros. */
export const processCounts: ComputedRef<ProcessCounts> = computed(() => state.value);

/**
 * Whether counts are meaningful — i.e. the server has pushed for this
 * connection, which only happens for a bound session. Drives the badge's
 * visibility; the numbers themselves may well be all-zero.
 */
export const countsAvailable: ComputedRef<boolean> = computed(() => received.value);

/** Apply a {@code process-counts} frame. */
export function setProcessCounts(data: ProcessCountsNotification): void {
  received.value = true;
  state.value = {
    running: data.running ?? 0,
    waiting: data.waiting ?? 0,
    blocked: data.blocked ?? 0,
    total: data.total ?? 0,
  };
}

/**
 * Back to zero — on disconnect and on session unbind, so a stale badge
 * doesn't outlive the session it counted.
 */
export function resetProcessCounts(): void {
  received.value = false;
  state.value = { ...ZERO };
}
