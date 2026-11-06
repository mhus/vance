import { ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { ToolServiceDto } from '@vance/generated';

/**
 * The tools a {@code tool_task} of this project can call, grouped by service,
 * with each service's catalog entry — what the palette lists. Loaded once on
 * demand and kept for the lifetime of the caller; {@code load} again to refresh
 * (a login made in the meantime changes {@code connected}).
 */
export function useWorkflowTools(projectId: () => string | undefined, lang: () => string) {
  const services = ref<ToolServiceDto[]>([]);
  const loaded = ref(false);
  const busy = ref(false);
  const error = ref<string | null>(null);

  async function load(): Promise<void> {
    const project = projectId();
    if (busy.value || !project) return;
    busy.value = true;
    error.value = null;
    try {
      services.value = await brainFetch<ToolServiceDto[]>(
        'GET',
        `project/${encodeURIComponent(project)}/workflow-tools?lang=${encodeURIComponent(lang())}`,
      );
      loaded.value = true;
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      busy.value = false;
    }
  }

  /** Load unless that already happened or is under way. */
  async function ensureLoaded(): Promise<void> {
    if (!loaded.value && !busy.value) await load();
  }

  return { services, loaded, busy, error, load, ensureLoaded };
}

/** What {@link useWorkflowTools} hands out; the palette and the parameter form share one. */
export type WorkflowTools = ReturnType<typeof useWorkflowTools>;
