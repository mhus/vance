import { computed, getCurrentInstance, type ComputedRef } from 'vue';

/**
 * The host's translation function, reachable from this bundle.
 *
 * <p>Duplicated from {@code @vance/components} on purpose — this package takes
 * no dependency on any other workspace package (see the note in
 * `extensions/composeOutputs.ts`), because it is bundled into every addon
 * remote that embeds the editor. A twenty-line helper is the cheaper of the two
 * couplings.
 *
 * <p>How it works: the host installs vue-i18n with {@code globalInjection},
 * which puts {@code $t} on {@code app.config.globalProperties}; a component
 * reaches it through its own {@code appContext}, which Vue inherits from
 * whoever renders it. Tiptap NodeViews are covered too — {@code VueRenderer}
 * copies the editor's {@code appContext} onto the node-view vnode.
 *
 * <p>Reactivity is preserved: {@code $t} reads the locale ref on every call, so
 * calling it during render registers the dependency.
 *
 * <p>Without an i18n installation the key itself is returned — a visible
 * {@code blockEditor.slash.image.title} is a bug report, a silent English
 * fallback is not.
 */
export type Translate = (key: string, named?: Record<string, unknown>) => string;

type GlobalTranslate = (key: string, named?: Record<string, unknown>) => unknown;

/** Resolve the host's {@code $t}. Call in {@code setup()}. */
export function useT(): Translate {
  const instance = getCurrentInstance();
  const raw = instance?.appContext.config.globalProperties.$t as GlobalTranslate | undefined;
  return toTranslate(raw);
}

/**
 * Same lookup for code that has an editor but no component instance — the
 * slash-menu item list, which is rebuilt on every keystroke outside of any
 * component's setup.
 */
export function translatorFor(
  appContext: { config?: { globalProperties?: Record<string, unknown> } } | null | undefined,
): Translate {
  return toTranslate(appContext?.config?.globalProperties?.$t as GlobalTranslate | undefined);
}

function toTranslate(raw: GlobalTranslate | undefined): Translate {
  if (typeof raw !== 'function') return (key) => key;
  return (key, named) => {
    const out = named ? raw(key, named) : raw(key);
    return typeof out === 'string' ? out : key;
  };
}

/**
 * The UI language, for {@code Intl} formatting. Falls back to {@code 'en'}
 * where no i18n is installed.
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
