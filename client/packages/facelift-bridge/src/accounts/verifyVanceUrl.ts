import { CapacitorHttp } from '@capacitor/core';

import type { HttpGetOptions, HttpGetResult } from '@vance/facelift-account-webview';

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
 * Transport: on native iOS/Android `CapacitorHttp` uses `URLSession`
 * — no CORS preflight, works for any remote origin. In the Electron
 * desktop shell CapacitorHttp would fall back to a browser `fetch`
 * (CORS-bound, and a Vance deployment is not expected to send CORS
 * headers on `/config.json`), so the request is routed through the
 * Electron main process (`window.faceliftDesktop.httpGet`) instead —
 * same no-CORS property as the native path. A plain browser (the
 * wrapper's Vite dev server) still goes through CapacitorHttp's web
 * fetch, subject to CORS.
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
    response = await fetchConfigJson(fullUrl);
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
      reason: `not a Vancetope instance (product=${JSON.stringify(parsed?.product)})`,
    };
  }
  return { ok: true, config: parsed };
}

/**
 * Fetch `<url>/config.json` via the transport that matches the host —
 * the Electron main process on the desktop shell (no CORS),
 * `CapacitorHttp` everywhere else (native URLSession on iOS/Android).
 * Returns the Capacitor `HttpResponse` shape either way; the
 * main-process variant is structurally compatible.
 */
async function fetchConfigJson(fullUrl: string): Promise<HttpGetResult> {
  const options: HttpGetOptions = {
    url: fullUrl,
    connectTimeout: 5000,
    readTimeout: 5000,
    headers: {
      Accept: 'application/json',
      'Cache-Control': 'no-cache, no-store',
      Pragma: 'no-cache',
    },
  };
  // `window.faceliftDesktop` is injected by the facelift-desktop
  // Electron preload; absent on native mobile and in a plain browser.
  const desktop = window.faceliftDesktop;
  if (desktop) {
    return desktop.httpGet(options);
  }
  return CapacitorHttp.get(options);
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
