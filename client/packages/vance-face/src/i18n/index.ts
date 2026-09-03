// vue-i18n setup for the Vance Web-UI.
//
// The i18n instance is a singleton — every editor's `main.ts` calls
// {@link installI18n} before mounting its app. Locale resolution
// order at startup:
//   1. {@code getActiveLanguage()} — reads `webui.language` straight
//      from the data cookie (server-refreshed on every profile save).
//   2. {@code navigator.language} primary tag (`'de-DE'` → `'de'`).
//   3. `'en'`.
//
// Fallback locale is always `'en'`. Missing keys in any other locale
// fall through to English silently — `silentTranslationWarn: true`
// keeps the console quiet about it.

import { createI18n, type I18n } from 'vue-i18n';
import type { App } from 'vue';
import { onI18nMessages } from '@vance/shared/i18n';
import { getActiveLanguage } from '@/platform';
import en from './en';
import de from './de';

export type Locale = 'en' | 'de';

const SUPPORTED_LOCALES: Locale[] = ['en', 'de'];

function isSupported(value: string): value is Locale {
  return (SUPPORTED_LOCALES as string[]).includes(value);
}

function resolveStartupLocale(): Locale {
  const fromActive = getActiveLanguage();
  if (fromActive && isSupported(fromActive)) return fromActive;
  if (typeof navigator !== 'undefined' && navigator.language) {
    const primary = navigator.language.toLowerCase().split('-')[0];
    if (isSupported(primary)) return primary;
  }
  return 'en';
}

const i18n: I18n<
  { en: typeof en; de: typeof de },
  Record<string, never>,
  Record<string, never>,
  string,
  false
> = createI18n({
  // Vue 3 composition-API mode — `legacy: false` enables `useI18n()`
  // in `<script setup>` and the global `$t` function.
  legacy: false,
  globalInjection: true,
  locale: resolveStartupLocale(),
  fallbackLocale: 'en',
  messages: { en, de },
  // Suppress the warning per missing key — fallback is intentional.
  silentTranslationWarn: true,
  silentFallbackWarn: true,
  missingWarn: false,
  fallbackWarn: false,
});

// Addon translations. A federation remote bundles its own copy of every
// workspace package and therefore cannot reach `i18n` here, so it drops its
// bundle into the globalThis registry in `@vance/shared/i18n` and this merges
// it in. Subscribing at module scope covers both directions of the race: the
// registry replays what a remote registered before this ran, and later
// registrations (a lazily loaded addon) arrive through the same sink.
//
// Merged, not assigned: `mergeLocaleMessage` deep-copies, so an addon may
// extend a host namespace — its `documents.detail.tab*` key lands next to the
// built-in ones instead of replacing the branch.
onI18nMessages((id, bundle) => {
  for (const [locale, messages] of Object.entries(bundle)) {
    if (!isSupported(locale)) continue;
    try {
      i18n.global.mergeLocaleMessage(locale, messages);
    } catch (e) {
      // A malformed bundle must not take the page down — the addon then shows
      // its keys, which is a visible but survivable defect.
      console.warn(`[i18n] bundle '${id}' (${locale}) could not be merged`, e);
    }
  }
});

/** Install on the given Vue app. Call before {@code app.mount()}. */
export function installI18n(app: App): void {
  app.use(i18n);
}

/**
 * Switch the live UI language. Used by the profile page after the
 * user picks a different language — flips the i18n locale ref so all
 * bound templates re-render immediately, without waiting for the
 * page reload that would otherwise re-read the data cookie.
 *
 * Unsupported codes silently no-op (rather than throwing) — the user
 * has already saved the value to the server, the UI just doesn't
 * have a translation file yet.
 */
export function setUiLocale(value: string | null): void {
  const next = value ?? 'en';
  if (!isSupported(next)) return;
  if (i18n.mode === 'composition') {
    // The exposed `.global.locale` is a Ref<string> when legacy is
    // false; assign through `.value`.
    (i18n.global.locale as unknown as { value: string }).value = next;
  } else {
    (i18n.global as unknown as { locale: string }).locale = next;
  }
}

export { i18n };
