import { type I18n } from 'vue-i18n';
import type { App } from 'vue';
import en from './en';
import de from './de';
export type Locale = 'en' | 'de';
declare const i18n: I18n<{
    en: typeof en;
    de: typeof de;
}, Record<string, never>, Record<string, never>, string, false>;
/** Install on the given Vue app. Call before {@code app.mount()}. */
export declare function installI18n(app: App): void;
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
export declare function setUiLocale(value: string | null): void;
export { i18n };
//# sourceMappingURL=index.d.ts.map