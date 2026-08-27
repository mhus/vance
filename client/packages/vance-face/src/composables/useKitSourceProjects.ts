import { ref, type Ref } from 'vue';
import type { KitSourceProjectDto } from '@vance/generated';
import { brainFetch } from '@vance/shared';

/**
 * The projects of this tenant that are themselves kit sources
 * ({@code GET /brain/{tenant}/admin/kits/source-projects}).
 *
 * <p>Two consumers, same list: the second dropdown in the create-project
 * dialog ("start from a kit that lives here") and the picker tab in the
 * install dialog. The server filters per entry — what comes back is what the
 * caller may read and what the kit says may be installed directly.
 *
 * <p>A failure resolves to an empty list rather than propagating. Being unable
 * to offer this is not an error the user has to act on: the url form and the
 * catalog dropdown are both still there, and a red banner about a picker
 * nobody asked for is worse than its absence. The message is kept for
 * diagnostics.
 */
export function useKitSourceProjects(): {
  projects: Ref<KitSourceProjectDto[]>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  load: () => Promise<void>;
} {
  const projects = ref<KitSourceProjectDto[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      projects.value = await brainFetch<KitSourceProjectDto[]>(
        'GET',
        'admin/kits/source-projects',
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load kit-source projects.';
      projects.value = [];
    } finally {
      loading.value = false;
    }
  }

  return { projects, loading, error, load };
}
