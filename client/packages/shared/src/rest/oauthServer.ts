import type {
  OAuthConsentDecisionRequest,
  OAuthConsentDecisionResponse,
  OAuthConsentRequestDto,
  OAuthGrantDto,
} from '@vance/generated';
import { brainFetch } from './restClient';

/**
 * The OAuth authorization server's browser-facing endpoints — the consent
 * page and the list of connected apps (grants). The protocol endpoints
 * (metadata, registration, token) are for OAuth clients, not for this UI.
 *
 * See `specification/public/oauth-server.md`.
 */

/** What a pending sign-in request asks for; binds it to the logged-in user. */
export async function getConsentRequest(requestId: string): Promise<OAuthConsentRequestDto> {
  return brainFetch<OAuthConsentRequestDto>(
    'GET',
    `oauth/authorize/request/${encodeURIComponent(requestId)}`,
  );
}

/** Approve or decline; the answer is where the browser must go next. */
export async function decideConsent(
  decision: OAuthConsentDecisionRequest,
): Promise<OAuthConsentDecisionResponse> {
  return brainFetch<OAuthConsentDecisionResponse>('POST', 'oauth/authorize/consent', {
    body: decision,
  });
}

/** The caller's own grants ("connected apps"), newest first. Never carries a token. */
export async function listOAuthGrants(): Promise<OAuthGrantDto[]> {
  return brainFetch<OAuthGrantDto[]>('GET', 'oauth/grants');
}

/** Revoke a grant — every access and refresh token of it stops working. */
export async function revokeOAuthGrant(grantId: string): Promise<void> {
  await brainFetch<void>('DELETE', `oauth/grants/${encodeURIComponent(grantId)}`);
}
