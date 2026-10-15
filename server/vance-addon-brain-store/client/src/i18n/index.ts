/**
 * i18n of the store addon surface.
 *
 * <p>Messages travel to the host's i18n instance through the globalThis
 * registry in {@code @vance/shared/i18n} — a federation remote cannot reach the
 * host's {@code I18n} object directly. Registering is a side effect of importing
 * this module, so a component that translates cannot render before its strings
 * arrived. The lookup goes through {@code useT()} of {@code @vance/components},
 * which reads the host's {@code $t} off the app context; no vue-i18n on this
 * package's dependency list.
 */
import { useT as useHostT, type Translate } from '@vance/components';
import { localeLoaders, registerI18nMessages } from '@vance/shared/i18n';
import en from './en';

// English is the source and ships with the bundle; every other locale is
// generated into ./locales by scripts/translate-client.py and loaded only when
// the user picks it.
registerI18nMessages(
  'store',
  { en },
  localeLoaders(import.meta.glob('./locales/*.json', { import: 'default' })),
);

/** The host's translator. Call in {@code setup()}. */
export function useT(): Translate {
  return useHostT();
}
