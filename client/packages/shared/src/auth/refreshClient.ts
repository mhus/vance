import type { RefreshTokenResponse } from '@vance/generated';
import { brainBaseUrl } from '../rest/restClient';
import { getJwt, getTenantId, getUsername, storeAuth } from './jwtStorage';

/**
 * `POST /brain/{tenant}/refresh` — re-mint the JWT in exchange for a still
 * valid one. The current token goes in the `Authorization` header; the server
 * does not need a request body.
 *
 * Returns the new token on success. Returns `null` if there is no current
 * token, no tenant on file, or the server refuses (the caller should treat
 * that as a hard logout).
 */
export async function refreshToken(): Promise<string | null> {
  const currentJwt = getJwt();
  const tenantId = getTenantId();
  const username = getUsername();
  if (!currentJwt || !tenantId || !username) return null;

  const response = await fetch(`${brainBaseUrl()}/brain/${encodeURIComponent(tenantId)}/refresh`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${currentJwt}` },
  });
  if (!response.ok) return null;

  const data = (await response.json()) as RefreshTokenResponse;
  storeAuth({ jwt: data.token, tenantId, username });
  return data.token;
}
