import { CapacitorHttp } from '@capacitor/core';

/**
 * Add-Account validation — checks that a user-supplied Brain URL
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
  const fullUrl = `${base}/config.json`;
  let response;
  try {
    response = await CapacitorHttp.get({
      url: fullUrl,
      connectTimeout: 5000,
      readTimeout: 5000,
      headers: { Accept: 'application/json' },
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
  let parsed: VanceConfigJson;
  try {
    parsed =
      typeof response.data === 'string'
        ? (JSON.parse(response.data) as VanceConfigJson)
        : (response.data as VanceConfigJson);
  } catch {
    return { ok: false, reason: 'response is not JSON' };
  }
  if (parsed?.product !== 'vance') {
    return { ok: false, reason: 'not a Vance instance' };
  }
  return { ok: true, config: parsed };
}
