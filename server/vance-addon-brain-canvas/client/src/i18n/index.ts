/**
 * i18n of the canvas addon surface.
 *
 * <p>Two halves, because a federation remote sits on the far side of the host's
 * Vue app:
 *
 * <ul>
 *   <li>The <b>messages</b> travel through the globalThis registry in
 *       {@code @vance/shared/i18n}. Registering happens as a side effect of
 *       importing this module — a component that translates imports {@link useT}
 *       from here, so its bundle cannot render before its strings arrived.</li>
 *   <li>The <b>lookup</b> goes through {@code useT()} of
 *       {@code @vance/components}, which reads the host's {@code $t} off the
 *       app context. No vue-i18n on this package's dependency list.</li>
 * </ul>
 */
import { useT as useHostT, type Translate } from '@vance/components';
import { registerI18nMessages } from '@vance/shared/i18n';
import en from './en';
import de from './de';

registerI18nMessages('canvas', { en, de });

/** The host's translator. Call in {@code setup()}. */
export function useT(): Translate {
  return useHostT();
}
