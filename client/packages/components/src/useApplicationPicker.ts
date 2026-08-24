/**
 * The two-step "pick an application, then a place inside it" state, shared by
 * every link picker.
 *
 * Two steps because the two questions cost different things: listing apps is a
 * manifest read each, listing one app's places is a folder scan. So the listing
 * is fetched once per dialog and the places only after a choice
 * (planning/inter-links.md §12.7).
 *
 * Kept here rather than in each picker because it was about to be written a
 * third time — the Vue-side twin of the same argument that put
 * `documents/search` in brain instead of in four addons.
 */
import { ref, toValue, type MaybeRefOrGetter, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  ApplicationEntryDto,
  ApplicationListResponse,
  ApplicationTargetDto,
  ApplicationTargetsResponse,
} from '@vance/generated';

export interface ApplicationPicker {
  /** The caller's starred apps, across projects. */
  starred: Ref<ApplicationEntryDto[]>;
  /** Apps in the project being worked in. */
  project: Ref<ApplicationEntryDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  /** The app whose places are shown, or null while an app is being chosen. */
  openApp: Ref<ApplicationEntryDto | null>;
  targets: Ref<ApplicationTargetDto[]>;
  targetsLoading: Ref<boolean>;
  targetsError: Ref<string | null>;
  /** Fetch the listing. Idempotent — safe to call on every tab switch. */
  load: () => Promise<void>;
  /** Step two: show this app's places. */
  choose: (app: ApplicationEntryDto) => Promise<void>;
  /** Back to the app list. */
  back: () => void;
}

export function useApplicationPicker(
  projectId: MaybeRefOrGetter<string>,
): ApplicationPicker {
  const starred = ref<ApplicationEntryDto[]>([]);
  const project = ref<ApplicationEntryDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  let loaded = false;

  const openApp = ref<ApplicationEntryDto | null>(null);
  const targets = ref<ApplicationTargetDto[]>([]);
  const targetsLoading = ref(false);
  const targetsError = ref<string | null>(null);

  async function load(): Promise<void> {
    if (loaded || loading.value) return;
    loading.value = true;
    error.value = null;
    try {
      const params = new URLSearchParams({ projectId: toValue(projectId) });
      const resp = await brainFetch<ApplicationListResponse>('GET', `applications?${params}`);
      starred.value = resp.starred ?? [];
      project.value = resp.project ?? [];
      loaded = true;
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Could not load applications';
    } finally {
      loading.value = false;
    }
  }

  async function choose(app: ApplicationEntryDto): Promise<void> {
    openApp.value = app;
    targets.value = [];
    targetsError.value = null;
    targetsLoading.value = true;
    try {
      // The app's own project, which is not necessarily the caller's: a starred
      // app lives wherever it lives.
      const params = new URLSearchParams({ projectId: app.project, path: app.path });
      const resp = await brainFetch<ApplicationTargetsResponse>(
        'GET',
        `applications/targets?${params}`,
      );
      targets.value = resp.targets ?? [];
    } catch (e) {
      targetsError.value = e instanceof Error ? e.message : 'Could not load places';
    } finally {
      targetsLoading.value = false;
    }
  }

  function back(): void {
    openApp.value = null;
  }

  return {
    starred, project, loading, error,
    openApp, targets, targetsLoading, targetsError,
    load, choose, back,
  };
}

/**
 * Rows grouped by their `group` — ungrouped first, groups in first-seen order.
 * The app decides the grouping; this only preserves the order it sent.
 */
export function groupTargets<T extends { group?: string | null }>(
  list: T[],
): { name: string | null; items: T[] }[] {
  const out: { name: string | null; items: T[] }[] = [];
  const byName = new Map<string | null, { name: string | null; items: T[] }>();
  for (const item of list) {
    const name = item.group || null;
    let bucket = byName.get(name);
    if (!bucket) {
      bucket = { name, items: [] };
      byName.set(name, bucket);
      out.push(bucket);
    }
    bucket.items.push(item);
  }
  return out;
}
