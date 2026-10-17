import { beforeEach, describe, expect, it, vi } from 'vitest';

const brainFetch = vi.fn();

class RestError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
  }
}

vi.mock('@vance/shared', () => ({
  brainFetch: (...args: unknown[]) => brainFetch(...args),
  RestError,
}));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }));

const { useAdminServerTools } = await import('./useAdminServerTools');

describe('useAdminServerTools', () => {
  beforeEach(() => {
    brainFetch.mockReset();
  });

  it('loads the tool types', async () => {
    brainFetch.mockResolvedValue([{ typeId: 'mcp_server' }]);
    const tools = useAdminServerTools();
    await tools.loadTypes();
    expect(brainFetch).toHaveBeenCalledWith('GET', 'admin/server-tool-types');
    expect(tools.types.value).toEqual([{ typeId: 'mcp_server' }]);
    expect(tools.error.value).toBeNull();
  });

  it('treats a forbidden type catalog as no catalog, not as an error', async () => {
    brainFetch.mockRejectedValue(new RestError(403, 'forbidden'));
    const tools = useAdminServerTools();
    await tools.loadTypes();
    expect(tools.types.value).toEqual([]);
    expect(tools.error.value).toBeNull();
  });

  it('reports any other failure of the type catalog', async () => {
    brainFetch.mockRejectedValue(new RestError(500, 'boom'));
    const tools = useAdminServerTools();
    await tools.loadTypes();
    expect(tools.error.value).toBe('boom');
  });

  it('clears an earlier error when the next operation starts', async () => {
    const tools = useAdminServerTools();
    brainFetch.mockRejectedValueOnce(new RestError(500, 'boom'));
    await tools.loadTypes();
    expect(tools.error.value).toBe('boom');

    brainFetch.mockResolvedValueOnce({ name: 'jira' });
    await tools.loadOne('acme', 'jira');
    expect(tools.error.value).toBeNull();
    expect(tools.current.value).toEqual({ name: 'jira' });
  });

  it('keeps the test failure as the error and rethrows it', async () => {
    brainFetch.mockRejectedValue(new RestError(502, 'unreachable'));
    const tools = useAdminServerTools();
    await expect(tools.test('acme', 'jira')).rejects.toThrow('unreachable');
    expect(tools.error.value).toBe('unreachable');
    expect(tools.busy.value).toBe(false);
  });
});
