import { ref } from 'vue';
import { brainFetch } from '@vance/shared';

/** One finding of the kind validation, as the server reports it. */
export interface KindFinding {
  level: 'error' | 'warning';
  location: string;
  code: string;
  message: string;
}

interface KindValidation {
  ok: boolean;
  errors: number;
  warnings: number;
  findings: KindFinding[];
}

/**
 * Asks the server what it thinks of the <em>saved</em> workflow — the same
 * parser a start runs. The endpoint reads the stored document by path, so
 * what is typed but not yet saved is not part of the answer.
 */
export function useWorkflowServerCheck(
  projectId: () => string | undefined,
  path: () => string | undefined,
) {
  const busy = ref(false);
  const result = ref<KindValidation | null>(null);
  const error = ref<string | null>(null);

  async function run(): Promise<void> {
    const project = projectId();
    const docPath = path();
    if (busy.value || !project || !docPath) return;
    busy.value = true;
    error.value = null;
    try {
      result.value = await brainFetch<KindValidation>(
        'GET',
        `documents/validate?projectId=${encodeURIComponent(project)}&path=${encodeURIComponent(docPath)}`,
      );
    } catch (e) {
      result.value = null;
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      busy.value = false;
    }
  }

  function clear(): void {
    result.value = null;
    error.value = null;
  }

  return { busy, result, error, run, clear };
}
