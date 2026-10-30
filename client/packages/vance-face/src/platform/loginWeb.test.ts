import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@vance/shared', () => ({ brainBaseUrl: () => 'https://vance.test' }));
vi.mock('./faceliftShareSetup', () => ({ pushShareCredentials: vi.fn() }));
vi.mock('./me', () => ({ clearMe: vi.fn() }));

import { fetchLoginMethods, LoginError, startEmailToken, verifyEmailToken } from './loginWeb';
import { pushShareCredentials } from './faceliftShareSetup';

const fetchMock = vi.fn();

function answer(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), { status });
}

describe('e-mail code login calls', () => {
  beforeEach(() => vi.stubGlobal('fetch', fetchMock));
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
  });

  describe('fetchLoginMethods', () => {
    it('asks for the methods under the tenant, or a placeholder when none is known', async () => {
      fetchMock.mockResolvedValue(answer(200, { password: false, emailToken: true, sso: false }));

      const methods = await fetchLoginMethods('acme');
      await fetchLoginMethods(null);

      expect(methods).toEqual({ password: false, emailToken: true, sso: false });
      expect(fetchMock.mock.calls[0][0]).toBe('https://vance.test/brain/acme/login-methods');
      expect(fetchMock.mock.calls[1][0]).toBe('https://vance.test/brain/_/login-methods');
    });

    it('answers null when the server cannot say', async () => {
      fetchMock.mockResolvedValueOnce(answer(404));
      expect(await fetchLoginMethods('acme')).toBeNull();

      fetchMock.mockRejectedValueOnce(new Error('offline'));
      expect(await fetchLoginMethods('acme')).toBeNull();
    });
  });

  describe('startEmailToken', () => {
    it('posts to the e-mail-token path with both names encoded', async () => {
      fetchMock.mockResolvedValue(answer(200, { transactionId: 'tx-1', expiresAtTimestamp: 42 }));

      const started = await startEmailToken({ tenant: 'acme', username: 'a b' });

      expect(started).toEqual({ transactionId: 'tx-1', expiresAtTimestamp: 42 });
      expect(fetchMock.mock.calls[0][0]).toBe('https://vance.test/brain/acme/access/a%20b/email-token');
      expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'POST', credentials: 'include' });
    });

    it('turns a refusal into a LoginError with its status', async () => {
      fetchMock.mockResolvedValue(answer(429));

      await expect(startEmailToken({ tenant: 'acme', username: 'alice' })).rejects.toMatchObject({
        name: 'LoginError',
        status: 429,
      });
    });
  });

  describe('verifyEmailToken', () => {
    it('sends handle and code, asks for cookies and refresh, and forwards the tokens', async () => {
      fetchMock.mockResolvedValue(answer(200, { token: 'access', refreshToken: 'refresh' }));

      await verifyEmailToken({ tenant: 'acme', username: 'alice', transactionId: 'tx-1', token: '123456789012' });

      expect(fetchMock.mock.calls[0][0]).toBe('https://vance.test/brain/acme/access/alice/email-token/verify');
      expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
        transactionId: 'tx-1',
        token: '123456789012',
        requestRefreshToken: true,
        requestCookies: true,
      });
      expect(pushShareCredentials).toHaveBeenCalledWith({
        tenant: 'acme',
        username: 'alice',
        token: 'access',
        refreshToken: 'refresh',
      });
    });

    it('reports a wrong code as 401 and forwards nothing', async () => {
      fetchMock.mockResolvedValue(answer(401));

      const failure = await verifyEmailToken({
        tenant: 'acme',
        username: 'alice',
        transactionId: 'tx-1',
        token: '000000000000',
      }).catch((e: unknown) => e);

      expect(failure).toBeInstanceOf(LoginError);
      expect((failure as LoginError).status).toBe(401);
      expect(pushShareCredentials).not.toHaveBeenCalled();
    });
  });
});
