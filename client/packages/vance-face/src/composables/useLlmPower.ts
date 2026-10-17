import { onUnmounted, ref, type Ref } from 'vue';
import { brainFetch } from '@vance/shared';
import type { LlmPowerStatusDto, LlmPowerUpdateRequest } from '@vance/generated';
import { converged, MAX_RETRIES, RETRY_MS, TENANT_KEY, userKey, verifyDelayMs } from '@/insights/llmPower';

/**
 * REST loader and switch actions for Insights → Power
 * (`GET|PUT /brain/{tenant}/llm-power`, `PUT …/users/{userId}`).
 *
 * After a switch moves, its key stays in `pending` until the brain confirms
 * that what is stored is also what a pod enforces: the first check runs at the
 * brain's `verifyAt` (next refresh tick on every pod plus a grace), further
 * checks every {@link RETRY_MS}. A switch that does not converge within
 * {@link MAX_RETRIES} lands in `unconfirmed` instead.
 */
export interface UseLlmPower {
  status: Ref<LlmPowerStatusDto | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  pending: Ref<Set<string>>;
  unconfirmed: Ref<Set<string>>;
  load: () => Promise<void>;
  setTenant: (enabled: boolean) => Promise<void>;
  setUser: (userId: string, enabled: boolean) => Promise<void>;
}

export function useLlmPower(): UseLlmPower {
  const status = ref<LlmPowerStatusDto | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const pending = ref<Set<string>>(new Set());
  const unconfirmed = ref<Set<string>>(new Set());

  let timer: ReturnType<typeof setTimeout> | null = null;
  let retries = 0;

  async function load(): Promise<void> {
    loading.value = true;
    error.value = null;
    try {
      status.value = await brainFetch<LlmPowerStatusDto>('GET', 'llm-power');
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to load the LLM power state.';
    } finally {
      loading.value = false;
    }
  }

  async function put(path: string, enabled: boolean, key: string): Promise<void> {
    error.value = null;
    const body: LlmPowerUpdateRequest = { enabled };
    try {
      const answer = await brainFetch<LlmPowerStatusDto>('PUT', path, { body });
      status.value = answer;
      unconfirmed.value = withoutKey(unconfirmed.value, key);
      pending.value = new Set(pending.value).add(key);
      // Every new change restarts the wait: it is due at the next tick.
      retries = 0;
      schedule(verifyDelayMs(answer));
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Failed to switch.';
    }
  }

  function schedule(delayMs: number): void {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => void verify(), delayMs);
  }

  async function verify(): Promise<void> {
    timer = null;
    await load();
    const current = status.value;
    if (!current) {
      if (retries++ < MAX_RETRIES) schedule(RETRY_MS);
      return;
    }
    const open = new Set<string>();
    for (const key of pending.value) {
      if (!converged(current, key)) open.add(key);
    }
    if (open.size === 0) {
      pending.value = new Set();
      return;
    }
    if (retries++ < MAX_RETRIES) {
      pending.value = open;
      schedule(RETRY_MS);
      return;
    }
    unconfirmed.value = new Set([...unconfirmed.value, ...open]);
    pending.value = new Set();
  }

  onUnmounted(() => {
    if (timer) clearTimeout(timer);
  });

  return {
    status,
    loading,
    error,
    pending,
    unconfirmed,
    load,
    setTenant: (enabled) => put('llm-power', enabled, TENANT_KEY),
    setUser: (userId, enabled) =>
      put(`llm-power/users/${encodeURIComponent(userId)}`, enabled, userKey(userId)),
  };
}

function withoutKey(set: Set<string>, key: string): Set<string> {
  const next = new Set(set);
  next.delete(key);
  return next;
}
