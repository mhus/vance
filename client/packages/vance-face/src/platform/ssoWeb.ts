import type { SsoOptionsDto } from '@vance/generated';
import { brainBaseUrl } from '@vance/shared';

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
}): string {
  const query = new URLSearchParams();
  if (params.email) query.set('email', params.email);
  if (params.tenant) query.set('tenant', params.tenant);
  if (params.next) query.set('next', params.next);
  const qs = query.toString();
  return `${brainBaseUrl()}/brain/_sso/start${qs ? `?${qs}` : ''}`;
}
