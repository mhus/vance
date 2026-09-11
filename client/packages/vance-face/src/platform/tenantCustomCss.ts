// Tenant-wide custom stylesheet for the web-UI shell.
//
// The brain serves the tenant's `_vance/config/custom.css` (operator-
// authored, sanitizer-filtered) at `GET /brain/{tenant}/ui/custom-css`.
// This module fetches it once per page load and injects it as a single
// `<style>` element at the end of the document head — last sheet in the
// cascade wins, so the tenant stylesheet beats DaisyUI / Tailwind output
// of equal specificity.
//
// Why a JS fetch + <style> instead of a plain `<link>`: the REST client's
// auth handling (401 → silent refresh → retry) runs in the fetch wrapper.
// A `<link>` element cannot carry an Authorization header, and while the
// web host uses HttpOnly cookies (which a same-origin `<link>` would
// transport), the same code path must keep working under bearer-mode
// hosts — `brainFetchText` is the one path that already does.
//
// Fail-open by design: a missing stylesheet (200, empty body) is the
// normal state of a fresh tenant, a failed fetch is a styling problem,
// not a content problem — we log and render without customization, no
// error banner, no blocked boot. The element stays (empty) so a later
// successful load has a stable injection point.

import { brainFetchText, getTenantId } from '@vance/shared';

const STYLE_ELEMENT_ID = 'vance-tenant-custom-css';

/**
 * Fetch the tenant custom CSS and inject it into the document head.
 * No-op without a session (login page) and safe to call repeatedly —
 * the style element is reused and only rewritten when the content
 * actually changed.
 */
export async function applyTenantCustomCss(): Promise<void> {
  const tenant = getTenantId();
  if (!tenant) return;
  try {
    const css = await brainFetchText('ui/custom-css');
    setStyleElement(css ?? '');
  } catch (e) {
    console.warn('tenantCustomCss: fetch failed, rendering without tenant customization', e);
  }
}

function setStyleElement(css: string): void {
  let element = document.getElementById(STYLE_ELEMENT_ID) as HTMLStyleElement | null;
  if (!element) {
    element = document.createElement('style');
    element.id = STYLE_ELEMENT_ID;
    document.head.appendChild(element);
  }
  if (element.textContent !== css) {
    element.textContent = css;
  }
}
