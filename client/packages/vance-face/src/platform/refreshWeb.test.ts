import { afterEach, describe, expect, it, vi } from 'vitest';

// Single-flight regression (code-review: refreshAccess must dedupe concurrent
// callers). Mock the collaborators so the test observes only the dedup.
vi.mock('./loginWeb', () => ({ silentLogin: vi.fn() }));
vi.mock('./webUiSession', () => ({
  getSessionData: vi.fn(() => ({ tenantId: 't', username: 'u' })),
  isRefreshAlive: vi.fn(() => true),
  hydrateIdentity: vi.fn(),
  clearLocalSessionData: vi.fn(),
}));

import { refreshAccessCookie } from './refreshWeb';
import { silentLogin, type SilentLoginOutcome } from './loginWeb';
import { clearLocalSessionData, isRefreshAlive } from './webUiSession';

const silentLoginMock = vi.mocked(silentLogin);
const clearLocalSessionDataMock = vi.mocked(clearLocalSessionData);

describe('refreshAccessCookie single-flight', () => {
  afterEach(() => vi.clearAllMocks());

  it('collapses concurrent callers into one silentLogin', async () => {
    let resolve!: (v: SilentLoginOutcome) => void;
    silentLoginMock.mockReturnValueOnce(new Promise<SilentLoginOutcome>((r) => { resolve = r; }));

    const a = refreshAccessCookie();
    const b = refreshAccessCookie();
    resolve('ok');

    expect(await a).toBe(true);
    expect(await b).toBe(true);
    expect(silentLoginMock).toHaveBeenCalledTimes(1);
  });

  it('starts a fresh refresh once the previous one has settled', async () => {
    silentLoginMock.mockResolvedValue('ok');

    await refreshAccessCookie();
    await refreshAccessCookie();

    expect(silentLoginMock).toHaveBeenCalledTimes(2);
  });

  it('clears the in-flight promise on rejection so a later call retries', async () => {
    silentLoginMock.mockRejectedValueOnce(new Error('boom'));
    await expect(refreshAccessCookie()).rejects.toThrow('boom');

    silentLoginMock.mockResolvedValueOnce('ok');
    expect(await refreshAccessCookie()).toBe(true);
    expect(silentLoginMock).toHaveBeenCalledTimes(2);
  });
});

// Login-loop regression: a corrupted access/refresh cookie leaves the
// JS-readable data cookie claiming a live session. If that claim survives
// a server refusal, `ensureAuthenticated` and `IndexApp` bounce the user
// between editor and index forever.
describe('refreshAccessCookie stale-session handling', () => {
  afterEach(() => vi.clearAllMocks());

  it('drops the local session data when the server rejects the refresh', async () => {
    silentLoginMock.mockResolvedValueOnce('rejected');

    expect(await refreshAccessCookie()).toBe(false);
    expect(clearLocalSessionDataMock).toHaveBeenCalledTimes(1);
  });

  it('keeps the local session data when the server is unreachable', async () => {
    silentLoginMock.mockResolvedValueOnce('failed');

    expect(await refreshAccessCookie()).toBe(false);
    expect(clearLocalSessionDataMock).not.toHaveBeenCalled();
  });

  it('keeps the local session data on success', async () => {
    silentLoginMock.mockResolvedValueOnce('ok');

    expect(await refreshAccessCookie()).toBe(true);
    expect(clearLocalSessionDataMock).not.toHaveBeenCalled();
  });

  it('drops the local session data when the cookie says refresh is spent', async () => {
    vi.mocked(isRefreshAlive).mockReturnValueOnce(false);

    expect(await refreshAccessCookie()).toBe(false);
    expect(silentLoginMock).not.toHaveBeenCalled();
    expect(clearLocalSessionDataMock).toHaveBeenCalledTimes(1);
  });
});
