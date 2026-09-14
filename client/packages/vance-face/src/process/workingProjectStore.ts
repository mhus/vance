import { computed, ref, type ComputedRef, type Ref } from 'vue';
import type { WorkingProjectNotification } from '@vance/generated';

/**
 * Which project the bound session's chat-process currently coordinates —
 * Eddie's "spot" — fed by the {@code working-project-changed} WebSocket
 * notification and rendered as a badge in the editor topbar.
 *
 * <p>Push-only, mirroring {@code processCountsStore}: the server sends one
 * frame at welcome / resume / bootstrap time and afterwards whenever the
 * spot actually moves (via the LLM {@code project_switch} tool, the WS
 * {@code project-switch} request, or Eddie's DELEGATE side-effect). No
 * polling fallback, no REST pull.
 *
 * <p>{@code null} is a legitimate state — no spot picked yet, or a non-hub
 * engine whose chat-process never sets one. The welcome push always
 * arrives, including with {@code null}, so a reconnecting client drops a
 * stale badge from a previous connection.
 *
 * <p>Module-level reactive singleton rather than a Pinia store, for the
 * same reason as {@code processCountsStore}: the badge lives inside
 * EditorShell, which every MPA entry-point inherits, and not every entry
 * registers Pinia.
 */

const state: Ref<string | null> = ref(null);

/** Current spot ({@code null} = no working project). */
export const workingProject: ComputedRef<string | null> = computed(() => state.value);

/** Apply a {@code working-project-changed} frame. */
export function setWorkingProject(data: WorkingProjectNotification): void {
  state.value = data.workingProject ?? null;
}

/**
 * Back to no spot — on disconnect and on session unbind, so a stale badge
 * doesn't outlive the session it described.
 */
export function resetWorkingProject(): void {
  state.value = null;
}
