import { computed, getCurrentInstance, type ComputedRef } from 'vue';

/**
 * The host's translation function, reachable from any bundle.
 *
 * <p>Why not {@code useI18n()}: this package (and every addon remote that
 * consumes it) must build and run without vue-i18n on its dependency list —
 * see the note in {@code FormFields.vue}. The host installs vue-i18n with
 * {@code globalInjection: true}, which puts {@code $t} on
 * {@code app.config.globalProperties}; a component reaches it through its own
 * {@code appContext} no matter which bundle the component's code came from,
 * because the app context is inherited from the parent that renders it. That is
 * exactly the situation of an addon Kind view: its code is a federation remote,
 * its component instance belongs to the host's app.
 *
 * <p>Reactivity is preserved. {@code $t} is the global composer's bound
 * {@code t}, which reads the locale ref on every call — invoked during render,
 * it registers the dependency, so a language switch re-renders the template.
 *
 * <p>Without an i18n installation (a component mounted in a bare app, or an
 * addon surface rendered before the host booted) the key itself is returned.
 * That is deliberate: a visible {@code canvas.board.note} is a bug report,
 * while a silent English fallback hides a missing bundle registration until a
 * German-speaking user finds it.
 */
export type Translate = (
  key: string,
  named?: Record<string, unknown>,
  plural?: number,
) => string;

type GlobalTranslate = (
  key: string,
  named?: Record<string, unknown>,
  plural?: number,
) => unknown;

/**
 * Resolve the host's {@code $t}. Call in {@code setup()} — outside a component
 * instance there is no app context to read, and the returned function then
 * echoes its keys.
 *
 * <p>The third parameter selects a plural form from a {@code 'one | many'}
 * message. It has to be passed explicitly — vue-i18n picks the branch from the
 * count argument, not from a named value, so {@code {n: 2}} alone would always
 * render the first branch.
 *
 * @example
 * const t = useT();
 * // template: {{ t('canvas.board.note') }}
 * // plural:   t('calendar.timeline.entryCount', { n }, n)
 */
export function useT(): Translate {
  const instance = getCurrentInstance();
  const raw = instance?.appContext.config.globalProperties.$t as
    | GlobalTranslate
    | undefined;
  if (typeof raw !== 'function') return (key) => key;
  return (key, named, plural) => {
    const out = plural !== undefined
      ? raw(key, named ?? {}, plural)
      : named ? raw(key, named) : raw(key);
    return typeof out === 'string' ? out : key;
  };
}

/**
 * The UI language, for {@code Intl} formatting.
 *
 * <p>Same route as {@link useT}: vue-i18n's {@code globalInjection} publishes
 * {@code $i18n} on the app context, and its {@code locale} accessor reads the
 * underlying ref — so a computed over it tracks a language switch and
 * re-formats dates without the component importing vue-i18n.
 *
 * <p>Falls back to {@code 'en'} where no i18n is installed, which is what
 * {@code Intl} would do with an undefined locale anyway, just explicitly.
 */
export function useLocale(): ComputedRef<string> {
  const instance = getCurrentInstance();
  const i18n = instance?.appContext.config.globalProperties.$i18n as
    | { locale?: unknown }
    | undefined;
  return computed(() => {
    const value = i18n?.locale;
    return typeof value === 'string' && value ? value : 'en';
  });
}
