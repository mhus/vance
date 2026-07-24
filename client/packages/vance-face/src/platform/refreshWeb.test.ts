import { afterEach, describe, expect, it, vi } from 'vitest';

// Single-flight regression (code-review: refreshAccess must dedupe concurrent
// callers). Mock the collaborators so the test observes only the dedup.
vi.mock('./loginWeb', () => ({ silentLogin: vi.fn() }));
vi.mock('./webUiSession', () => ({
  getSessionData: vi.fn(() => ({ tenantId: 't', username: 'u' })),
  isRefreshAlive: vi.fn(() => true),
  hydrateIdentity: vi.fn(),
}));

import { refreshAccessCookie } from './refreshWeb';
import { silentLogin } from './loginWeb';

const silentLoginMock = vi.mocked(silentLogin);

describe('refreshAccessCookie single-flight', () => {
  afterEach(() => vi.clearAllMocks());

  it('collapses concurrent callers into one silentLogin', async () => {
    let resolve!: (v: boolean) => void;
    silentLoginMock.mockReturnValueOnce(new Promise<boolean>((r) => { resolve = r; }));

    const a = refreshAccessCookie();
    const b = refreshAccessCookie();
    resolve(true);

    expect(await a).toBe(true);
    expect(await b).toBe(true);
    expect(silentLoginMock).toHaveBeenCalledTimes(1);
  });

  it('starts a fresh refresh once the previous one has settled', async () => {
    silentLoginMock.mockResolvedValue(true);

    await refreshAccessCookie();
    await refreshAccessCookie();

    expect(silentLoginMock).toHaveBeenCalledTimes(2);
  });

  it('clears the in-flight promise on rejection so a later call retries', async () => {
    silentLoginMock.mockRejectedValueOnce(new Error('boom'));
    await expect(refreshAccessCookie()).rejects.toThrow('boom');

    silentLoginMock.mockResolvedValueOnce(true);
    expect(await refreshAccessCookie()).toBe(true);
    expect(silentLoginMock).toHaveBeenCalledTimes(2);
  });
});
