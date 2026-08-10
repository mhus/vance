import { computed, ref, type ComputedRef, type Ref } from 'vue';
import type { ProcessCountsNotification } from '@vance/generated';

/**
 * How many think-processes of the bound session are running / waiting /
 * blocked — fed by the {@code process-counts} WebSocket notification and
 * rendered as a badge in the editor topbar.
 *
 * <p>Push-only: the server sends one frame at welcome/resume time and
 * afterwards whenever the numbers change (per-turn RUNNING↔IDLE flapping is
 * coalesced away server-side). There is no polling fallback and no REST
 * pull — an editor without a bound session simply keeps zeros and the badge
 * stays hidden.
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

/** Current counts. Never null — an unbound editor reads zeros. */
export const processCounts: ComputedRef<ProcessCounts> = computed(() => state.value);

/** True once at least one process (excluding the session chat) exists. */
export const hasProcesses: ComputedRef<boolean> = computed(() => state.value.total > 0);

/** Apply a {@code process-counts} frame. */
export function setProcessCounts(data: ProcessCountsNotification): void {
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
  state.value = { ...ZERO };
}
