import { computed, watch } from 'vue';
import { brainFetch } from '@vance/shared';
import { useEvents } from '@/composables/useEvents';
import { useSchedulers } from '@/composables/useSchedulers';
import { sourcesOf } from './workflowSources';

/**
 * What starts one workflow — the events and schedulers of the project that
 * name it — kept current: loaded when the workflow or project changes, and
 * again after the person adds or removes one.
 */
export function useWorkflowSources(
  projectId: () => string | undefined,
  workflow: () => string | null,
) {
  const events = useEvents();
  const schedulers = useSchedulers();

  const sources = computed(() => {
    const name = workflow();
    return name ? sourcesOf(events.events.value, schedulers.schedulers.value, name) : [];
  });
  /** Names already taken by an event or a scheduler, for naming a new one. */
  const takenNames = computed(() => [
    ...events.events.value.map((e) => e.name),
    ...schedulers.schedulers.value.map((s) => s.name),
  ]);
  const loading = computed(() => events.loading.value || schedulers.loading.value);
  const error = computed(() => events.error.value ?? schedulers.error.value);

  async function reload(): Promise<void> {
    const project = projectId();
    if (!project) return;
    await Promise.all([events.loadProject(project), schedulers.loadProject(project)]);
  }

  watch([projectId, workflow], () => { void reload(); }, { immediate: true });

  /** Remove a source made from a catalog trigger (the service is told too) or a scheduler. */
  async function remove(kind: 'event' | 'scheduler', name: string): Promise<void> {
    const project = projectId();
    if (!project) return;
    if (kind === 'scheduler') {
      await schedulers.remove(project, name);
    } else {
      await brainFetch<void>(
        'DELETE',
        `project/${encodeURIComponent(project)}/workflow-triggers/${encodeURIComponent(name)}`,
      );
    }
    await reload();
  }

  return { sources, takenNames, loading, error, reload, remove };
}
