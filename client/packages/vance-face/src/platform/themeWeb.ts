// Apply the active web-UI theme to the DOM. Two surfaces need to
// agree on the resolved theme:
//
//   * DaisyUI components — driven by `<html data-theme="…">`. With the
//     attribute unset, DaisyUI falls back to its own prefers-color-scheme
//     handling, which would diverge from our "auto" — so we always
//     write the resolved value, never leave it blank.
//   * Tailwind utilities (`dark:bg-…`) — controlled by Tailwind's
//     {@code darkMode: 'class'} strategy + the `dark` class on `<html>`.
//
// "Auto" resolves at apply time via {@code matchMedia} and is kept in
// sync with system changes through a single mediaQuery listener that
// is registered the first time {@link applyTheme} runs and re-uses the
// same handler for every later call.

import { getActiveTheme, type WebUiTheme } from './webUiSession';

type ResolvedTheme = 'light' | 'dark';

const DARK_QUERY = '(prefers-color-scheme: dark)';

let mediaQueryList: MediaQueryList | null = null;
let mediaQueryHandler: ((event: MediaQueryListEvent) => void) | null = null;

function resolveTheme(value: WebUiTheme): ResolvedTheme {
  if (value === 'light' || value === 'dark') return value;
  if (typeof window === 'undefined' || !window.matchMedia) return 'light';
  return window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light';
}

function paintResolved(resolved: ResolvedTheme): void {
  const root = document.documentElement;
  root.dataset.theme = resolved;
  root.classList.toggle('dark', resolved === 'dark');
}

/**
 * Apply {@code value} (or the active stored theme when omitted) to the
 * document. Idempotent — safe to call from boot, profile saves, or
 * any other re-entry point. Sets up (or tears down) the media-query
 * listener so "auto" tracks the OS preference live.
 */
export function applyTheme(value?: WebUiTheme): void {
  const next: WebUiTheme = value ?? getActiveTheme();
  paintResolved(resolveTheme(next));

  if (next === 'auto') {
    ensureAutoListener();
  } else {
    detachAutoListener();
  }
}

function ensureAutoListener(): void {
  if (mediaQueryList !== null) return;
  if (typeof window === 'undefined' || !window.matchMedia) return;
  const mql = window.matchMedia(DARK_QUERY);
  const handler = (): void => {
    // Re-check the active theme inside the listener — if the user
    // pinned to light/dark since registration, drop ourselves.
    const current = getActiveTheme();
    if (current !== 'auto') {
      detachAutoListener();
      return;
    }
    paintResolved(mql.matches ? 'dark' : 'light');
  };
  mql.addEventListener('change', handler);
  mediaQueryList = mql;
  mediaQueryHandler = handler;
}

function detachAutoListener(): void {
  if (mediaQueryList === null || mediaQueryHandler === null) return;
  mediaQueryList.removeEventListener('change', mediaQueryHandler);
  mediaQueryList = null;
  mediaQueryHandler = null;
}
