import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

const brainFetch = vi.fn();
vi.mock('@vance/shared', () => ({ brainFetch: (...args: unknown[]) => brainFetch(...args) }));

const { useStorageCleanup } = await import('./useStorageCleanup');

/**
 * Pure composable contract for {@link useStorageCleanup}. The dialog owns the
 * "are you sure?" UX; the composable owns "does the URL build right, does
 * the error surface, does the breakdown refresh after a cleanup".
 */

function fakeBreakdown(): { tenant: string; usedBytes: number } {
  return { tenant: 'acme', usedBytes: 1_000_000 };
}

function fakeResult(freedBytes: number): { freedBytes: number; action: string } {
  return { freedBytes, action: 'trash' };
}

describe('useStorageCleanup', () => {
  beforeEach(() => {
    brainFetch.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loadBreakdown asks for tenant-wide when projectId is missing', async () => {
    brainFetch.mockResolvedValue(fakeBreakdown());
    const api = useStorageCleanup();

    const ok = await api.loadBreakdown(null);

    expect(ok).toBe(true);
    expect(brainFetch).toHaveBeenCalledWith('GET', 'storage/breakdown');
    expect(api.breakdown.value?.tenant).toBe('acme');
  });

  it('loadBreakdown scopes by projectId when present', async () => {
    brainFetch.mockResolvedValue(fakeBreakdown());
    const api = useStorageCleanup();

    await api.loadBreakdown('demo');

    expect(brainFetch).toHaveBeenCalledWith('GET', 'storage/breakdown?projectId=demo');
  });

  it('emptyTrash POSTs to the cleanup endpoint and refreshes the breakdown', async () => {
    // First call = POST cleanup/trash, second call = GET storage/breakdown.
    brainFetch.mockResolvedValueOnce(fakeResult(123));
    brainFetch.mockResolvedValueOnce(fakeBreakdown());

    const api = useStorageCleanup();
    const result = await api.emptyTrash('demo');

    expect(result.freedBytes).toBe(123);
    expect(brainFetch).toHaveBeenCalledTimes(2);
    expect(brainFetch.mock.calls[0][0]).toBe('POST');
    expect(brainFetch.mock.calls[0][1]).toBe('cleanup/trash?projectId=demo');
    expect(brainFetch.mock.calls[1][0]).toBe('GET');
    expect(brainFetch.mock.calls[1][1]).toBe('storage/breakdown?projectId=demo');
    // Breakdown is repopulated, so the next paint reflects the freed bytes.
    expect(api.breakdown.value?.tenant).toBe('acme');
  });

  it('thinArchives POSTs keepLatest in the body', async () => {
    brainFetch.mockResolvedValueOnce({ freedBytes: 50, action: 'archives', keepLatest: 5 });
    brainFetch.mockResolvedValueOnce(fakeBreakdown());

    const api = useStorageCleanup();
    const result = await api.thinArchives(null, 5);

    expect(result.action).toBe('archives');
    expect(brainFetch.mock.calls[0][0]).toBe('POST');
    expect(brainFetch.mock.calls[0][1]).toBe('cleanup/archives');
    // The keepLatest depth is the second arg of the {body:…} wrapper.
    expect(brainFetch.mock.calls[0][2]).toEqual({ body: { keepLatest: 5 } });
  });

  it('failed emptyTrash surfaces the message and re-throws', async () => {
    brainFetch.mockRejectedValueOnce(new Error('quota exceeded'));
    const api = useStorageCleanup();

    await expect(api.emptyTrash(null)).rejects.toThrow('quota exceeded');
    expect(api.error.value).toBe('quota exceeded');
    // Breakdown was not refreshed — a failed cleanup must not overwrite the
    // visible numbers with stale ones.
    expect(api.breakdown.value).toBeNull();
  });

  it('reset clears state', async () => {
    brainFetch.mockResolvedValueOnce(fakeBreakdown());
    const api = useStorageCleanup();
    await api.loadBreakdown(null);

    api.reset();

    expect(api.breakdown.value).toBeNull();
    expect(api.error.value).toBeNull();
  });
});
