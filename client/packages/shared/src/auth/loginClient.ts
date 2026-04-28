import type { AccessTokenRequest, AccessTokenResponse } from '@vance/generated';
import { brainBaseUrl } from '../rest/restClient';
import { storeAuth } from './jwtStorage';

export class LoginError extends Error {
  constructor(public readonly status: number, message: string) {
    super(message);
    this.name = 'LoginError';
  }
}

/**
 * `POST /brain/{tenant}/access/{username}` — exchange username + password
 * for a freshly-minted JWT and persist it via `storeAuth`.
 *
 * Throws `LoginError` on any non-2xx response. The Brain returns 401 with no
 * body for any failure (unknown user, wrong password, deactivated account)
 * to prevent user enumeration — we surface that as a generic error.
 */
export async function login(params: {
  tenant: string;
  username: string;
  password: string;
}): Promise<void> {
  const body: AccessTokenRequest = { password: params.password };
  const url = `${brainBaseUrl()}/brain/${encodeURIComponent(params.tenant)}/access/${encodeURIComponent(params.username)}`;

  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    throw new LoginError(response.status, response.status === 401
      ? 'Invalid credentials.'
      : `Login failed with status ${response.status}.`);
  }

  const data = (await response.json()) as AccessTokenResponse;
  storeAuth({ jwt: data.token, tenantId: params.tenant, username: params.username });
}
