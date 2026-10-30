import { beforeEach, describe, expect, it, vi } from 'vitest';

const brainFetch = vi.fn();
vi.mock('@vance/shared', () => ({
  brainFetch: (...args: unknown[]) => brainFetch(...args),
}));

import { loadTenantTitle } from './tenantTitle';

describe('loadTenantTitle', () => {
  beforeEach(() => {
    brainFetch.mockReset();
  });

  it('asks the cascade for ui.title without a project', async () => {
    brainFetch.mockResolvedValue({ 'ui.title': 'Acme' });
    expect(await loadTenantTitle()).toBe('Acme');
    expect(brainFetch).toHaveBeenCalledWith('GET', 'settings/cascade?key=ui.title');
  });

  it('trims the value', async () => {
    brainFetch.mockResolvedValue({ 'ui.title': '  Acme  ' });
    expect(await loadTenantTitle()).toBe('Acme');
  });

  it('reads an unset or blank title as none', async () => {
    brainFetch.mockResolvedValue({});
    expect(await loadTenantTitle()).toBeNull();
    brainFetch.mockResolvedValue({ 'ui.title': '   ' });
    expect(await loadTenantTitle()).toBeNull();
  });

  it('reads a failed request as none', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    brainFetch.mockRejectedValue(new Error('offline'));
    expect(await loadTenantTitle()).toBeNull();
    expect(warn).toHaveBeenCalledOnce();
    warn.mockRestore();
  });
});
