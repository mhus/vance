import { ref, watch, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type {
  ScriptValidateResponse,
  ScriptDeepValidateResponse,
} from '@vance/generated';
import type { CortexDocument } from '../types';

/**
 * Quick + deep validation state for the script in one editor tab.
 *
 * <p>Quick = parse-only syntax check (cheap). Deep = LLM-driven static
 * review (slow, cached server-side via {@code lastDeepReviewedHash}).
 * Both endpoints accept either {@code scriptId} (server loads the
 * body) or inline {@code code}; we send {@code code} so unsaved edits
 * validate too.
 *
 * <p>Extracted from the former {@code CortexValidateDialog}: the two
 * runs are now toolbar actions and their output lands in an inline
 * panel, so the fetching had to move out of the modal that used to own
 * both.
 */
export interface ScriptValidation {
  /** Panel visibility — a run opens it, ✕ closes it. */
  readonly open: Ref<boolean>;
  readonly quickResult: Ref<ScriptValidateResponse | null>;
  readonly deepResult: Ref<ScriptDeepValidateResponse | null>;
  readonly quickBusy: Ref<boolean>;
  readonly deepBusy: Ref<boolean>;
  readonly error: Ref<string | null>;
  runQuick(): Promise<void>;
  runDeep(): Promise<void>;
  close(): void;
}

export function useScriptValidation(doc: () => CortexDocument): ScriptValidation {
  const open = ref(false);
  const quickResult = ref<ScriptValidateResponse | null>(null);
  const deepResult = ref<ScriptDeepValidateResponse | null>(null);
  const quickBusy = ref(false);
  const deepBusy = ref(false);
  const error = ref<string | null>(null);

  function reset(): void {
    open.value = false;
    quickResult.value = null;
    deepResult.value = null;
    error.value = null;
  }

  function body(): Record<string, unknown> {
    const d = doc();
    return { scriptId: d.id, code: d.inlineText, sourceName: d.path };
  }

  async function runQuick(): Promise<void> {
    if (quickBusy.value) return;
    quickBusy.value = true;
    error.value = null;
    open.value = true;
    try {
      quickResult.value = await brainFetch<ScriptValidateResponse>(
        'POST', 'scripts/validate', { body: body() },
      );
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Validate failed';
    } finally {
      quickBusy.value = false;
    }
  }

  async function runDeep(): Promise<void> {
    if (deepBusy.value) return;
    deepBusy.value = true;
    error.value = null;
    open.value = true;
    try {
      deepResult.value = await brainFetch<ScriptDeepValidateResponse>(
        'POST', 'scripts/validate-deep', { body: body() },
      );
      // The server caches lastDeepReviewedHash; it arrives with the
      // next DTO load via dtoToDocument. No client-side hashing.
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Deep validate failed';
    } finally {
      deepBusy.value = false;
    }
  }

  // Tab switch: results belong to the document they were produced
  // for — carrying them over would attribute one script's warnings to
  // another.
  watch(() => doc().id, reset);

  return {
    open,
    quickResult,
    deepResult,
    quickBusy,
    deepBusy,
    error,
    runQuick,
    runDeep,
    close: reset,
  };
}
