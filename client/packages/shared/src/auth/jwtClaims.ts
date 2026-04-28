// Lightweight client-side view of a Vance JWT. Mirrors the fields exposed by
// `de.mhus.vance.shared.jwt.VanceJwtClaims` on the server.
//
// We never *trust* what we decode here — the server re-verifies on every
// request. This decoder is only for UI decisions: showing the username, or
// triggering a refresh before the token expires.

export interface VanceJwtClaims {
  /** From `sub` — the username. */
  username: string;
  /** From `tid` — the tenant id the token was issued for. */
  tenantId: string;
  /** From `exp` — Unix epoch in milliseconds. */
  expiresAtMs: number;
  /** From `iat` — Unix epoch in milliseconds, or `null` if absent. */
  issuedAtMs: number | null;
}

/**
 * Decode the payload segment of a JWT without verifying its signature.
 * Returns `null` if the token is malformed or missing required claims.
 */
export function decodeJwt(token: string): VanceJwtClaims | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;

  let payloadJson: unknown;
  try {
    const padded = parts[1].padEnd(parts[1].length + ((4 - (parts[1].length % 4)) % 4), '=');
    const base64 = padded.replace(/-/g, '+').replace(/_/g, '/');
    payloadJson = JSON.parse(atob(base64));
  } catch {
    return null;
  }

  if (!isObject(payloadJson)) return null;
  const sub = payloadJson['sub'];
  const tid = payloadJson['tid'];
  const exp = payloadJson['exp'];
  if (typeof sub !== 'string' || typeof tid !== 'string' || typeof exp !== 'number') {
    return null;
  }
  const iat = typeof payloadJson['iat'] === 'number' ? (payloadJson['iat'] as number) * 1000 : null;
  return {
    username: sub,
    tenantId: tid,
    expiresAtMs: exp * 1000,
    issuedAtMs: iat,
  };
}

/**
 * Whether the given token is still valid right now, with an optional safety
 * margin. Default margin: 30 seconds — i.e. tokens that expire in under
 * 30s are considered already expired so callers refresh proactively.
 */
export function isTokenValid(token: string, marginMs = 30_000): boolean {
  const claims = decodeJwt(token);
  if (!claims) return false;
  return claims.expiresAtMs - marginMs > Date.now();
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null;
}
