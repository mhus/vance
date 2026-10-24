import { computed, ref, shallowRef, watch, type ComputedRef, type Ref } from 'vue';
import type { HomeStatusDto, MeDto } from '@vance/generated';
import { loadMe, meState } from '@/platform/me';

/**
 * The signed-in user for a view — one request (`GET /brain/{tenant}/me`)
 * carries identity, roles, UI settings and the status hints next to the
 * tiles (LLM power, budget, storage quota).
 *
 * <p>All views share one answer (`@/platform/me`); `load` refreshes it.
 * Each status field is independently nullable — the SPA renders nothing for
 * a null, so a transient outage never reads as "off" or "full" when the
 * actual answer was "unknown".
 */
export interface UseMe {
  me: Readonly<Ref<MeDto | null>>;
  status: ComputedRef<HomeStatusDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  /** Refresh; with a project the own project role and its budget come along. */
  load: (project?: string | null) => Promise<boolean>;
}

export function useMe(): UseMe {
  const me = meState();
  const status = computed<HomeStatusDto | null>(() => me.value?.status ?? null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(project?: string | null): Promise<boolean> {
    loading.value = true;
    error.value = null;
    try {
      const answer = await loadMe(project);
      if (!answer) error.value = 'Failed to load the current user.';
      return answer !== null;
    } finally {
      loading.value = false;
    }
  }

  return { me, status, loading, error, load };
}

/**
 * {@link useMe} for one project — re-loads whenever {@code projectRef}
 * changes, so the session picker's budget banner follows the selected project
 * without the parent wiring a watch.
 *
 * <p>The project-scoped answer stays in this view: it is kept in a local ref
 * and never replaces the shared tenant-level answer. Until it is in (and
 * without a project) the shared answer is shown. A late answer for a project
 * that is no longer selected is dropped.
 */
export function useProjectMe(projectRef: Ref<string | null | undefined>): UseMe {
  const shared = meState();
  const scoped = shallowRef<MeDto | null>(null);
  const me = computed<MeDto | null>(() => scoped.value ?? shared.value);
  const status = computed<HomeStatusDto | null>(() => me.value?.status ?? null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(project?: string | null): Promise<boolean> {
    const wanted = project ?? null;
    loading.value = true;
    error.value = null;
    try {
      const answer = await loadMe(wanted);
      if ((projectRef.value ?? null) !== wanted) return answer !== null;
      if (!answer) {
        error.value = 'Failed to load the current user.';
        return false;
      }
      scoped.value = wanted ? answer : null;
      return true;
    } finally {
      loading.value = false;
    }
  }

  watch(
    projectRef,
    (project) => {
      scoped.value = null;
      void load(project ?? null);
    },
    { immediate: true },
  );
  return { me, status, loading, error, load };
}
