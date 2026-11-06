import { ref } from 'vue';
import { WorkflowEditError } from './workflowMutations';

/**
 * Runs changes against the workflow text and hands the result on. A change
 * the mutation layer refuses ({@link WorkflowEditError}) is kept as a message
 * to show; anything else is a bug and propagates.
 */
export function useWorkflowChange(
  doc: () => string,
  emitDoc: (next: string) => void,
) {
  const error = ref<string | null>(null);

  /** @returns whether the change was made */
  function apply(change: () => string): boolean {
    try {
      const next = change();
      error.value = null;
      if (next !== doc()) emitDoc(next);
      return true;
    } catch (e) {
      if (!(e instanceof WorkflowEditError)) throw e;
      error.value = e.message;
      return false;
    }
  }

  return { error, apply };
}
