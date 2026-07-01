import { onBeforeUnmount, ref, watch, type Ref } from 'vue';
import type { SessionGroupsUiStateDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

const SAVE_DEBOUNCE_MS = 300;

/**
 * Per-user, per-project collapse state for session-group blocks, persisted
 * across reloads/devices under {@code me/ui-state/session-groups} — mirrors
 * the project-sidebar collapse persistence in {@code ProjectListSidebar}.
 *
 * Keys are stored namespaced as {@code <projectId>::<key>} so equally-named
 * groups in different projects don't share state (session-group names are
 * only unique per project). Callers pass bare keys (group name, or the
 * ungrouped sentinel); namespacing happens here. Best-effort — a failed
 * GET/PUT is swallowed (worst case: collapse doesn't stick).
 */
export function useSessionGroupCollapse(projectId: Ref<string | null | undefined>): {
  has: (key: string) => boolean;
  toggle: (key: string) => void;
} {
  // Bare keys collapsed in the current project (drives rendering).
  const current = ref<Set<string>>(new Set());
  // Full namespaced set across all projects (what we persist).
  const allKeys = ref<Set<string>>(new Set());
  let loaded = false;
  let saveTimer: number | null = null;

  function prefix(): string {
    return `${projectId.value ?? ''}::`;
  }

  function deriveCurrent(): void {
    const p = prefix();
    const next = new Set<string>();
    for (const k of allKeys.value) {
      if (k.startsWith(p)) next.add(k.slice(p.length));
    }
    current.value = next;
  }

  async function load(): Promise<void> {
    try {
      const state = await brainFetch<SessionGroupsUiStateDto>(
        'GET', 'me/ui-state/session-groups');
      allKeys.value = new Set(state.collapsedKeys ?? []);
    } catch (e) {
      console.warn('Failed to load session-group UI state', e);
    } finally {
      loaded = true;
      deriveCurrent();
    }
  }

  function scheduleSave(): void {
    if (!loaded) return;
    if (saveTimer !== null) window.clearTimeout(saveTimer);
    saveTimer = window.setTimeout(() => {
      saveTimer = null;
      void saveNow();
    }, SAVE_DEBOUNCE_MS);
  }

  async function saveNow(): Promise<void> {
    try {
      await brainFetch<SessionGroupsUiStateDto>('PUT', 'me/ui-state/session-groups', {
        body: { collapsedKeys: Array.from(allKeys.value) } satisfies SessionGroupsUiStateDto,
      });
    } catch (e) {
      console.warn('Failed to save session-group UI state', e);
    }
  }

  function has(key: string): boolean {
    return current.value.has(key);
  }

  function toggle(key: string): void {
    const cur = new Set(current.value);
    const all = new Set(allKeys.value);
    const nsKey = prefix() + key;
    if (cur.has(key)) {
      cur.delete(key);
      all.delete(nsKey);
    } else {
      cur.add(key);
      all.add(nsKey);
    }
    current.value = cur;
    allKeys.value = all;
    scheduleSave();
  }

  watch(projectId, () => deriveCurrent());
  void load();

  onBeforeUnmount(() => {
    if (saveTimer !== null) {
      window.clearTimeout(saveTimer);
      saveTimer = null;
      void saveNow();
    }
  });

  return { has, toggle };
}
