/**
 * The connection-string format, and nothing else.
 *
 * <p><b>Deliberately import-free.</b> Both ends of this format have to read it:
 * the web UI, which mints it, and an outside tool — a browser extension, a
 * shell one-liner — which consumes it. The consumer has no brain session, no
 * `configurePlatform()`, no REST client; pulling this out of a module that
 * imports those would either drag the whole client into a 30-kB extension or
 * force a second copy of the format. A second copy of a format definition is
 * how two ends stop agreeing.
 *
 * <p>{@link connectionBlobFor} — which needs the current session to *build*
 * one — lives next door in `integrationConnection.ts`.
 *
 * Wire form:
 *
 * ```
 * vancetope1.<base64url(json)>.<checksum>
 * ```
 */

/** Format marker. A pasted blob has to be recognisable in a config file. */
export const CONNECTION_PREFIX = 'vancetope1';

export interface ConnectionBlob {
  /** Base URL of the brain, as the browser reached it. */
  brainUrl: string;
  tenant: string;
  /** Project the token is pinned to. */
  projectId: string;
  /**
   * Where inside the project the tool should write — for the links app the
   * app folder.
   *
   * <p>Not a permission: the token is confined to the *project*, and a tool
   * that changes this reaches another list in the same project. It is a
   * destination, not a fence, and any UI that shows it should say so.
   */
  target?: string;
  /**
   * Scope profile ids, carried for diagnostics — the server re-reads them
   * anyway. A list because one tool routinely carries more than one
   * capability and is still set up once.
   */
  profiles: string[];
  /** The signed JWT. */
  token: string;
  /** Unix millis, so a tool can warn before it stops working. */
  expiresAt?: number;
}

/**
 * FNV-1a, 32 bit, as 8 hex chars.
 *
 * <p><b>This is a damage check, not a security control.</b> The token inside is
 * signed — a mangled one fails verification and the brain answers 401. What the
 * signature does *not* cover is everything around it: the brain URL, the
 * project, the target folder. A truncated paste there produces a 404 much
 * later, from a failure that looks nothing like "you pasted half a string".
 * Catching that locally, before the first call, is the whole job.
 *
 * <p>Deliberately not SHA-256: `crypto.subtle` is async and needs a secure
 * context, and a tool verifying this may be a content script or a shell
 * one-liner. A sync, dependency-free digest keeps the far end trivial.
 */
export function connectionChecksum(payload: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < payload.length; i++) {
    hash ^= payload.charCodeAt(i);
    // The FNV prime, via shifts: a plain multiply overflows into doubles.
    hash = (hash + ((hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24))) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

function base64url(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64url(text: string): string {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/** Assemble the pasteable string. */
export function encodeConnectionBlob(blob: ConnectionBlob): string {
  const payload = base64url(JSON.stringify(blob));
  return `${CONNECTION_PREFIX}.${payload}.${connectionChecksum(payload)}`;
}

/**
 * Read one back, or `null` when it is not one of ours, is damaged, or was
 * truncated. One `null` for every failure on purpose: the far end's only
 * useful reaction is "ask for the string again", and distinguishing *how* it
 * was broken would just be more ways to get the error text wrong.
 */
export function decodeConnectionBlob(text: string): ConnectionBlob | null {
  const parts = text.trim().split('.');
  if (parts.length !== 3 || parts[0] !== CONNECTION_PREFIX) return null;
  const [, payload, checksum] = parts;
  if (connectionChecksum(payload) !== checksum) return null;
  try {
    const parsed = JSON.parse(fromBase64url(payload)) as ConnectionBlob;
    if (!parsed.brainUrl || !parsed.tenant || !parsed.projectId || !parsed.token) return null;
    return parsed;
  } catch {
    return null;
  }
}
