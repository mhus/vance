import { computed, watch } from 'vue';
import { brainFetch } from '@vance/shared';
import { useEvents } from '@/composables/useEvents';
import { useSchedulers } from '@/composables/useSchedulers';
import { sourcesOf, type WorkflowSource } from './workflowSources';

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

  /**
   * Remove a source. One made from a catalog trigger goes through the catalog (it tells the service, or forgets
   * what a poll remembered); any other event is deleted with the token made for it, any other scheduler as it is.
   */
  async function remove(source: WorkflowSource): Promise<void> {
    const project = projectId();
    if (!project) return;
    const name = encodeURIComponent(source.name);
    const base = `project/${encodeURIComponent(project)}`;
    if (source.from) {
      // Made from a catalog trigger: the catalog also tells the service, or forgets what a poll remembered.
      await brainFetch<void>('DELETE', `${base}/workflow-triggers/${name}`);
    } else if (source.kind === 'scheduler') {
      await schedulers.remove(project, source.name);
    } else {
      await brainFetch<void>('DELETE', `${base}/events/${name}`);
    }
    await reload();
  }

  return { sources, takenNames, loading, error, reload, remove };
}
