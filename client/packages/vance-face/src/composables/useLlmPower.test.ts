import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MAX_RETRIES, RETRY_MS, TENANT_KEY } from '@/insights/llmPower';

const brainFetch = vi.fn();
vi.mock('@vance/shared', () => ({ brainFetch: (...args: unknown[]) => brainFetch(...args) }));

const { useLlmPower } = await import('./useLlmPower');

/** A status in which stored and enforced agree — it looks converged. */
function agreed(enabled: boolean): unknown {
  return {
    enabled,
    effectiveEnabled: enabled,
    serverTime: '2026-10-17T10:00:00Z',
    verifyAt: '2026-10-17T10:00:00Z',
    users: [],
  };
}

describe('useLlmPower', () => {
  beforeEach(() => {
    brainFetch.mockReset();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('confirms a switch once a reload shows it enforced', async () => {
    brainFetch.mockResolvedValueOnce(agreed(false)).mockResolvedValueOnce(agreed(false));
    const power = useLlmPower();

    await power.setTenant(false);
    expect(power.pending.value.has(TENANT_KEY)).toBe(true);
    await vi.advanceTimersByTimeAsync(0);

    expect(power.pending.value.size).toBe(0);
    expect(power.unconfirmed.value.size).toBe(0);
  });

  it('never confirms on a failed reload, and ends unconfirmed when every check fails', async () => {
    brainFetch.mockImplementation((method: string) =>
      method === 'PUT' ? Promise.resolve(agreed(false)) : Promise.reject(new Error('boom')),
    );
    const power = useLlmPower();

    await power.setTenant(false);
    await vi.advanceTimersByTimeAsync(0);
    expect(power.pending.value.has(TENANT_KEY)).toBe(true);

    await vi.advanceTimersByTimeAsync(RETRY_MS * (MAX_RETRIES + 1));

    expect(power.pending.value.size).toBe(0);
    expect(power.unconfirmed.value.has(TENANT_KEY)).toBe(true);
    expect(power.error.value).toBe('boom');
  });

  it('load reports whether it succeeded', async () => {
    brainFetch.mockResolvedValueOnce(agreed(true)).mockRejectedValueOnce(new Error('down'));
    const power = useLlmPower();

    expect(await power.load()).toBe(true);
    expect(await power.load()).toBe(false);
    expect(power.status.value).not.toBeNull();
  });
});
