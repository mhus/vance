import { beforeEach, describe, expect, it, vi } from 'vitest';

const brainFetch = vi.fn();

vi.mock('@vance/shared', () => ({
  brainFetch: (...args: unknown[]) => brainFetch(...args),
}));

import { isTenantLlmConfigured } from './llmSettings';

describe('isTenantLlmConfigured', () => {
  beforeEach(() => {
    brainFetch.mockReset();
  });

  it('asks the tenant cascade for both default keys', async () => {
    brainFetch.mockResolvedValue({});
    await isTenantLlmConfigured();
    expect(brainFetch).toHaveBeenCalledWith(
      'GET', 'settings/cascade?key=ai.default.provider&key=ai.default.model');
  });

  it('is true when provider and model are set', async () => {
    brainFetch.mockResolvedValue({ 'ai.default.provider': 'openrouter', 'ai.default.model': 'x' });
    expect(await isTenantLlmConfigured()).toBe(true);
  });

  it('is false when one of the pair is missing or blank', async () => {
    brainFetch.mockResolvedValue({ 'ai.default.provider': 'openrouter', 'ai.default.model': ' ' });
    expect(await isTenantLlmConfigured()).toBe(false);
    brainFetch.mockResolvedValue({});
    expect(await isTenantLlmConfigured()).toBe(false);
  });

  it('is unknown, not false, when the request fails', async () => {
    brainFetch.mockRejectedValue(new Error('offline'));
    expect(await isTenantLlmConfigured()).toBeNull();
  });
});
