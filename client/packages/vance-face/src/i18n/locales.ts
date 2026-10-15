// The languages of the Web-UI — the one list the i18n setup, the topbar menu
// and the profile dropdown share.
//
// English (en.ts) is the source and the only hand-maintained language. Every
// other entry is generated from it into locales/<code>.json by
// scripts/translate-client.py, which carries the same list; add a language
// there and here together.

export const UI_LOCALES = [
  { code: 'en', label: 'English' },
  { code: 'de', label: 'Deutsch' },
  { code: 'fr', label: 'Français' },
  { code: 'es', label: 'Español' },
  { code: 'ja', label: '日本語' },
  { code: 'pt', label: 'Português' },
  { code: 'zh', label: '中文' },
  { code: 'pl', label: 'Polski' },
  { code: 'ru', label: 'Русский' },
] as const;

export type Locale = (typeof UI_LOCALES)[number]['code'];

export function isUiLocale(value: string): value is Locale {
  return UI_LOCALES.some((l) => l.code === value);
}

/**
 * Locales whose plural has three forms (one | few | many) instead of
 * English's two — "1 plik | 2 pliki | 5 plików". The translator writes three
 * variants for them, and {@link pluralIndex} picks one by the CLDR rules the
 * browser ships in {@code Intl.PluralRules}.
 */
export const THREE_FORM_LOCALES = ['pl', 'ru'] as const;

const CATEGORY_INDEX: Record<string, number> = { one: 0, few: 1 };

/**
 * vue-i18n plural rule for {@link THREE_FORM_LOCALES}. vue-i18n takes the
 * choice from an explicit plural argument or from a named `count` / `n`, and
 * passes -1 when there is neither — then the general form (the last one) reads
 * best. A message with fewer than three variants (an English fallback that
 * slipped through) keeps English's rule, so it never indexes past its own
 * variants.
 */
export function pluralIndex(locale: string, choice: number, choicesLength: number): number {
  if (choicesLength < 3) return Math.abs(choice) === 1 ? 0 : 1;
  if (choice < 0) return choicesLength - 1;
  return CATEGORY_INDEX[new Intl.PluralRules(locale).select(Math.abs(choice))] ?? 2;
}
