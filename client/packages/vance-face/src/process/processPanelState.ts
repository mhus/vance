import { ref, type Ref } from 'vue';

/**
 * Open/closed state of the process panel. Shared as a module-level ref for
 * the same reason as {@code processCountsStore}: the trigger lives in
 * {@code EditorTopbar} (inside the badge) while the panel itself is mounted
 * by {@code EditorShell}, and the shell layer deliberately doesn't depend on
 * Pinia.
 *
 * <p>Requirement: planning/process-visibility.md §4.B
 */
export const processPanelOpen: Ref<boolean> = ref(false);

export function openProcessPanel(): void {
  processPanelOpen.value = true;
}

export function closeProcessPanel(): void {
  processPanelOpen.value = false;
}
