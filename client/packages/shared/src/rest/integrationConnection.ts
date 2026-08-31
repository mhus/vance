import { brainBaseUrl } from './restClient';
import { getTenantId } from '../auth/jwtStorage';
import { encodeConnectionBlob } from './integrationConnectionCodec';

/**
 * Building a connection string for a token just minted in *this* session.
 *
 * <p>The format itself is in {@link ./integrationConnectionCodec} and imports
 * nothing — the far end that consumes one of these has no session to read.
 * This file is the half that does.
 */

/**
 * <p>`brainBaseUrl()` is `''` for the same-origin web build — the page's own
 * origin is then the answer, and an empty string in the blob would send the
 * far end nowhere.
 *
 * <p>Assembled in the browser rather than on the server because
 * {@code brainUrl} is the URL that actually reached this brain. A server
 * behind a reverse proxy routinely does not know its own external address — it
 * is the classic source of a value that looks right and points nowhere.
 */
export function connectionBlobFor(args: {
  projectId: string;
  target?: string;
  profile: string;
  token: string;
  expiresAt?: number;
}): string {
  const base = brainBaseUrl() || (typeof window === 'undefined' ? '' : window.location.origin);
  return encodeConnectionBlob({
    brainUrl: base.replace(/\/+$/, ''),
    tenant: getTenantId() ?? '',
    projectId: args.projectId,
    target: args.target,
    profile: args.profile,
    token: args.token,
    expiresAt: args.expiresAt,
  });
}
