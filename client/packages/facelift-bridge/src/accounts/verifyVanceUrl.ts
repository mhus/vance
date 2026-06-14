import { CapacitorHttp } from '@capacitor/core';

/**
 * Add-Account validation — checks that a user-supplied URL
 * actually points at a Vance deployment before persisting it.
 *
 * The check is a single GET to `<url>/config.json`, the file that
 * the `vance-face` docker entrypoint writes at pod start (and the
 * committed `public/config.json` fallback for non-docker dev
 * builds). We accept the URL when the file parses as JSON and has
 * `product === "vance"`.
 *
 * Uses `CapacitorHttp` rather than browser `fetch` so the request
 * goes through iOS `URLSession` natively — no CORS preflight,
 * works for any remote origin the user types in. Falls back to a
 * browser `fetch` when running outside a native context (so the
 * wrapper's Vite dev server still gets meaningful behaviour,
 * subject to CORS).
 *
 * Does NOT protect against intentional fraud (a malicious server
 * can serve a matching `config.json`). It does protect against
 * accidents — the user can't save `https://google.de` as an
 * account by typo because google.de has no `/config.json` with
 * the magic shape.
 */
export interface VanceConfigJson {
  product: string;
  schema?: number;
  version?: string;
  deployment?: string;
  hostname?: string;
  buildSha?: string;
  /** Server-defined human label (e.g. "Eddie", "Vance Production"). */
  title?: string;
  /** Optional URL back to the operator's home page. */
  backlink?: string;
}

export interface VerifyResult {
  ok: boolean;
  /** Parsed config when {@code ok} is true — UI may surface
   *  `version` / `deployment` for confirmation. */
  config?: VanceConfigJson;
  /** Short reason for failure, suitable for surfacing to the user. */
  reason?: string;
}

export async function verifyVanceUrl(url: string): Promise<VerifyResult> {
  const base = url.trim().replace(/\/+$/, '');
  if (base.length === 0) return { ok: false, reason: 'empty URL' };
  try {
    // eslint-disable-next-line no-new
    new URL(base);
  } catch {
    return { ok: false, reason: 'not a valid URL' };
  }
  // Cache-bust to defeat the iOS URLCache. Earlier failed attempts
  // (the website wasn't redeployed yet, so /config.json fell through
  // to nginx's SPA fallback returning index.html) get cached with
  // their HTML body — without the query string the next attempt
  // serves the stale HTML even after the server is fixed.
  const fullUrl = `${base}/config.json?_=${Date.now()}`;
  let response;
  try {
    response = await CapacitorHttp.get({
      url: fullUrl,
      connectTimeout: 5000,
      readTimeout: 5000,
      headers: {
        Accept: 'application/json',
        'Cache-Control': 'no-cache, no-store',
        Pragma: 'no-cache',
      },
    });
  } catch (e) {
    return {
      ok: false,
      reason: e instanceof Error ? e.message : 'network error',
    };
  }
  if (response.status !== 200) {
    return { ok: false, reason: `HTTP ${response.status}` };
  }
  // The website's nginx usually has a `try_files … /index.html`
  // SPA fallback — fetching a path that doesn't exist (like an
  // outdated face without /config.json) returns 200 + the SPA's
  // index.html. Catch that early via Content-Type before trying to
  // parse the body, otherwise the "response is not JSON" error
  // would point at random bytes the user can't act on.
  const contentType = readHeader(response.headers, 'content-type');
  if (contentType !== undefined && !contentType.toLowerCase().includes('json')) {
    return {
      ok: false,
      reason: `expected JSON, got "${contentType}" — /config.json missing? Redeploy vance-face.`,
    };
  }
  let parsed: VanceConfigJson | undefined;
  const dataType = typeof response.data;
  if (response.data !== null && response.data !== undefined && dataType === 'object') {
    parsed = response.data as VanceConfigJson;
  } else if (dataType === 'string') {
    const text = response.data as string;
    try {
      parsed = JSON.parse(text) as VanceConfigJson;
    } catch {
      const excerpt = text.slice(0, 120).replace(/\s+/g, ' ');
      return {
        ok: false,
        reason: `response is not JSON (got ${text.length} chars: "${excerpt}…")`,
      };
    }
  } else {
    return { ok: false, reason: `unexpected response type "${dataType}"` };
  }
  if (parsed?.product !== 'vance') {
    return {
      ok: false,
      reason: `not a Vance instance (product=${JSON.stringify(parsed?.product)})`,
    };
  }
  return { ok: true, config: parsed };
}

/** Case-insensitive header lookup — CapacitorHttp normalises header
 *  case on iOS but returns them as-shipped on Android, and the JS
 *  type is `Record<string, string>` either way. */
function readHeader(
  headers: Record<string, string> | undefined,
  name: string,
): string | undefined {
  if (headers === undefined) return undefined;
  const lower = name.toLowerCase();
  for (const key of Object.keys(headers)) {
    if (key.toLowerCase() === lower) return headers[key];
  }
  return undefined;
}
