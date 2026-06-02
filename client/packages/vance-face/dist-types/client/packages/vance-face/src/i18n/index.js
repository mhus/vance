// vue-i18n setup for the Vance Web-UI.
//
// The i18n instance is a singleton — every editor's `main.ts` calls
// {@link installI18n} before mounting its app. Locale resolution
// order at startup:
//   1. {@code getActiveLanguage()} — sessionStorage override set
//      by the profile page; lives for the lifetime of this tab.
//   2. data cookie's {@code webui.language} (already covered by
//      getActiveLanguage's fallback).
//   3. {@code navigator.language} primary tag (`'de-DE'` → `'de'`).
//   4. `'en'`.
//
// Fallback locale is always `'en'`. Missing keys in any other locale
// fall through to English silently — `silentTranslationWarn: true`
// keeps the console quiet about it.
import { createI18n } from 'vue-i18n';
import { getActiveLanguage } from '@/platform';
import en from './en';
import de from './de';
const SUPPORTED_LOCALES = ['en', 'de'];
function isSupported(value) {
    return SUPPORTED_LOCALES.includes(value);
}
function resolveStartupLocale() {
    const fromActive = getActiveLanguage();
    if (fromActive && isSupported(fromActive))
        return fromActive;
    if (typeof navigator !== 'undefined' && navigator.language) {
        const primary = navigator.language.toLowerCase().split('-')[0];
        if (isSupported(primary))
            return primary;
    }
    return 'en';
}
const i18n = createI18n({
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
/** Install on the given Vue app. Call before {@code app.mount()}. */
export function installI18n(app) {
    app.use(i18n);
}
/**
 * Switch the live UI language. Used by the profile page after the
 * user picks a different language — the new value flows from
 * sessionStorage (set by {@code setActiveLanguage}) into i18n's
 * reactive locale ref so all bound templates re-render immediately.
 *
 * Unsupported codes silently no-op (rather than throwing) — the user
 * has already saved the value to the server, the UI just doesn't
 * have a translation file yet.
 */
export function setUiLocale(value) {
    const next = value ?? 'en';
    if (!isSupported(next))
        return;
    if (i18n.mode === 'composition') {
        // The exposed `.global.locale` is a Ref<string> when legacy is
        // false; assign through `.value`.
        i18n.global.locale.value = next;
    }
    else {
        i18n.global.locale = next;
    }
}
export { i18n };
//# sourceMappingURL=index.js.map