import type { AccessTokenResponse, SsoOptionsDto, SsoRedeemRequest } from '@vance/generated';
import { brainBaseUrl, isFacelift } from '@vance/shared';

/**
 * SSO sign-in from the login page (planning/sso-login.md §5.1). The endpoints
 * sit under `/brain/_sso/` — no tenant in the path, the login does not know
 * one yet. All of them are open; they answer `null` here on any failure so
 * the page simply does not offer what it cannot reach.
 */

async function getOptions(path: string): Promise<SsoOptionsDto | null> {
  try {
    const response = await fetch(`${brainBaseUrl()}${path}`, { credentials: 'include' });
    if (!response.ok) return null;
    return (await response.json()) as SsoOptionsDto;
  } catch {
    return null;
  }
}

/**
 * What the login page may offer: `domains` — some e-mail domain signs in via
 * SSO here (ask for the work address); `sso` — the bound `tenant` accepts a
 * private Google account (offer the button directly).
 */
export function fetchSsoOptions(tenant: string | null): Promise<SsoOptionsDto | null> {
  const query = tenant ? `?tenant=${encodeURIComponent(tenant)}` : '';
  return getOptions(`/brain/_sso/options${query}`);
}

/** Whether `email` signs in via SSO — by its domain only; never names the tenant. */
export function discoverSso(email: string): Promise<SsoOptionsDto | null> {
  return getOptions(`/brain/_sso/discover?email=${encodeURIComponent(email)}`);
}

/**
 * Where the browser goes to start the sign-in. A real navigation: the server
 * answers with a redirect to the identity provider and sets the state cookie.
 */
export function ssoStartUrl(params: {
  email?: string | null;
  tenant?: string | null;
  next?: string | null;
  appChallenge?: string | null;
}): string {
  const query = new URLSearchParams();
  if (params.email) query.set('email', params.email);
  if (params.tenant) query.set('tenant', params.tenant);
  if (params.next) query.set('next', params.next);
  if (params.appChallenge) query.set('app_challenge', params.appChallenge);
  const qs = query.toString();
  return `${brainBaseUrl()}/brain/_sso/start${qs ? `?${qs}` : ''}`;
}

// ─── native apps (facelift iOS / desktop) ─────────────────────────────
//
// Google refuses sign-ins inside embedded WebViews, and a sign-in in the
// system browser would leave its cookies there. So inside the app the page
// asks the wrapper to run the sign-in in the system browser
// (`window.vanceFacelift.browserSignIn`: iOS ASWebAuthenticationSession,
// desktop default browser + loopback). The server hands back a one-time code
// instead of cookies; this WebView redeems it with the PKCE verifier only it
// holds, and the cookies land in its own partition.

interface BrowserSignInBridge {
  /** Opens `startUrl` (the wrapper appends `app_redirect`) and resolves with the URL the sign-in returned to. */
  browserSignIn?(startUrl: string): Promise<string>;
}

function signInBridge(): BrowserSignInBridge | null {
  if (!isFacelift()) return null;
  const b = (window as unknown as { vanceFacelift?: BrowserSignInBridge }).vanceFacelift;
  return b?.browserSignIn === undefined ? null : b;
}

/** Whether this page runs in a wrapper that can sign in through the system browser. */
export function canSignInViaApp(): boolean {
  return signInBridge() !== null;
}

/** Outcome of {@link signInViaApp}. */
export type AppSignInOutcome =
  | { kind: 'ok'; response: AccessTokenResponse }
  | { kind: 'rejected' }
  | { kind: 'failed' };

/**
 * The whole app sign-in: PKCE pair, system-browser round trip through the
 * wrapper, redeem. The verifier never leaves this function.
 */
export async function signInViaApp(params: {
  email?: string | null;
  tenant?: string | null;
  next?: string | null;
}): Promise<AppSignInOutcome> {
  const bridge = signInBridge();
  if (bridge?.browserSignIn === undefined) return { kind: 'failed' };
  const verifier = randomVerifier();
  const challenge = await s256(verifier);
  const start = new URL(ssoStartUrl({ ...params, appChallenge: challenge }), window.location.origin).toString();
  let returned: URL;
  try {
    returned = new URL(await bridge.browserSignIn(start));
  } catch {
    return { kind: 'failed' };
  }
  const error = returned.searchParams.get('error');
  if (error) return { kind: error === 'rejected' ? 'rejected' : 'failed' };
  const code = returned.searchParams.get('code');
  if (!code) return { kind: 'failed' };
  const body: SsoRedeemRequest = { code, verifier };
  try {
    const response = await fetch(`${brainBaseUrl()}/brain/_sso/redeem`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!response.ok) return { kind: 'failed' };
    return { kind: 'ok', response: (await response.json()) as AccessTokenResponse };
  } catch {
    return { kind: 'failed' };
  }
}

function randomVerifier(): string {
  const bytes = new Uint8Array(48);
  crypto.getRandomValues(bytes);
  return base64Url(bytes);
}

async function s256(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
