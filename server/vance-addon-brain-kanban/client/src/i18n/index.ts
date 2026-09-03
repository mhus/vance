/**
 * i18n of the kanban addon surface.
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
import { registerI18nMessages } from '@vance/shared/i18n';
import en from './en';
import de from './de';

registerI18nMessages('kanban', { en, de });

/** The host's translator. Call in {@code setup()}. */
export function useT(): Translate {
  return useHostT();
}
