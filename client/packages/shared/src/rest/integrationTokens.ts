import type {
  IntegrationScopeProfileDto,
  IntegrationTokenCreateRequest,
  IntegrationTokenDto,
} from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * Integration tokens — long-lived, narrowed credentials for outside tools.
 *
 * <p>A core brain surface rather than an addon one: the credential belongs to
 * the account, not to whichever app happens to offer the button that mints it.
 * The links app is the first caller; a general "my tokens" list in the profile
 * page is the obvious second, and it must not have to reimplement this.
 *
 * See `specification/public/integration-tokens.md`.
 */

/** Profiles this brain can mint for. The mint form's only source of truth. */
export async function listScopeProfiles(): Promise<IntegrationScopeProfileDto[]> {
  return brainFetch<IntegrationScopeProfileDto[]>('GET', 'integration-tokens/profiles');
}

/**
 * The caller's own tokens, newest first.
 *
 * Never carries a token value — the server keeps no copy it could show again.
 * Everything here is metadata: what it is for, where it points, when it was
 * last used.
 */
export async function listIntegrationTokens(): Promise<IntegrationTokenDto[]> {
  return brainFetch<IntegrationTokenDto[]>('GET', 'integration-tokens');
}

/**
 * Mint one. **The response is the only time the token value exists** — it is
 * not stored, so a caller that drops it has to revoke and mint again. Any UI
 * built on this has to say so before the dialog can be closed.
 */
export async function createIntegrationToken(
  request: IntegrationTokenCreateRequest,
): Promise<IntegrationTokenDto> {
  return brainFetch<IntegrationTokenDto>('POST', 'integration-tokens', { body: request });
}

/** Revoke. Takes effect within the server's liveness-cache window. */
export async function revokeIntegrationToken(tokenId: string): Promise<void> {
  await brainFetch<void>('DELETE', `integration-tokens/${encodeURIComponent(tokenId)}`);
}

/** A token that is neither revoked nor past its expiry. */
export function integrationTokenIsLive(token: IntegrationTokenDto): boolean {
  if (token.revokedAtTimestamp) return false;
  return !token.expiresAtTimestamp || token.expiresAtTimestamp > Date.now();
}
