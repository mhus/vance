/**
 * Cross-bundle message registry for the UI's single i18n instance.
 *
 * <p>The problem it solves: an addon's federation remote bundles its own copy of
 * every workspace package, so it cannot reach the {@code I18n} object the host
 * created — only the host's Vue app knows it. But an addon's strings belong to
 * the addon, not to `vance-face/src/i18n/en.ts`; a remote that ships
 * independently must be able to bring its own translations.
 *
 * <p>So the same trick {@code @vance/kind-registry} uses: the store lives on
 * {@code globalThis}, which every duplicated copy of this module shares.
 * An addon calls {@link registerI18nMessages} from its `register()` expose,
 * the host subscribes once with {@link onI18nMessages} and merges whatever
 * arrives into its live i18n instance.
 *
 * <p>Registration order does not matter. {@link onI18nMessages} replays every
 * bundle registered before the host subscribed, and every later registration
 * reaches the sink — which is what makes lazily loaded remotes work: the addon
 * is pulled the first time one of its kinds is opened, long after the host
 * booted.
 *
 * <p>Two kinds of locales travel through it. English (the source, always
 * needed as the fallback) is shipped eagerly. Every other locale is generated
 * from it by `scripts/translate-client.py` into `locales/<lang>.json` and
 * shipped as a loader, so a bundle only pulls the language the user picked —
 * see {@link localeLoaders}.
 *
 * <p>Platform-neutral on purpose (no Vue, no vue-i18n): this package is the one
 * place both ends of the federation boundary can agree on.
 */

/** One locale's message tree, in vue-i18n's nested shape. */
export type LocaleMessageTree = Record<string, unknown>;

/** A bundle as an addon ships it: locale code → message tree. */
export type MessageBundle = Record<string, LocaleMessageTree>;

/** Locales loaded on demand: locale code → loader of that locale's tree. */
export type LazyMessageBundle = Record<string, () => Promise<LocaleMessageTree>>;

/**
 * Receives a bundle. {@code lazy} is absent when the bundle was registered by an
 * addon built against an older copy of this module — the store is shared across
 * versions, so its shape only ever grows.
 */
type MessageSink = (id: string, bundle: MessageBundle, lazy?: LazyMessageBundle) => void;

interface Registry {
  /** Bundle id (addon id) → its eager messages. Re-registering replaces, HMR-friendly. */
  bundles: Map<string, MessageBundle>;
  /** Bundle id → its lazy locales. Optional: an older copy creates the store without it. */
  lazy?: Map<string, LazyMessageBundle>;
  sinks: Set<MessageSink>;
}

declare global {
  var __VANCE_I18N_MESSAGES__: Registry | undefined;
}

function store(): Registry {
  let s = globalThis.__VANCE_I18N_MESSAGES__;
  if (!s) {
    s = { bundles: new Map(), sinks: new Set() };
    globalThis.__VANCE_I18N_MESSAGES__ = s;
  }
  return s;
}

/**
 * Contribute translations to the host's i18n instance.
 *
 * @param id     bundle id, conventionally the addon id — re-registering the
 *               same id replaces the previous bundle so HMR does not stack
 *               copies.
 * @param bundle locale code → message tree, available at once. Merged into the
 *               host's messages per locale, so an addon may extend a host
 *               namespace (a {@code tabLabelKey} under `documents.detail`) as
 *               well as own its own (`canvas.*`). Locales the host does not
 *               support are ignored by the host, not here.
 * @param lazy   locale code → loader, for the generated locales. The host calls
 *               a loader only when that locale is (or becomes) active.
 */
export function registerI18nMessages(
  id: string,
  bundle: MessageBundle,
  lazy: LazyMessageBundle = {},
): void {
  const s = store();
  s.bundles.set(id, bundle);
  (s.lazy ??= new Map()).set(id, lazy);
  for (const sink of s.sinks) sink(id, bundle, lazy);
}

/**
 * Turn the result of {@code import.meta.glob('./locales/*.json', { import: 'default' })}
 * into a {@link LazyMessageBundle}: the file name (`fr.json`) is the locale code.
 * Takes the plain record so this package stays free of any bundler types.
 */
export function localeLoaders(
  modules: Record<string, () => Promise<unknown>>,
): LazyMessageBundle {
  const out: LazyMessageBundle = {};
  for (const [path, load] of Object.entries(modules)) {
    const match = /([^/]+)\.json$/.exec(path);
    if (!match) continue;
    out[match[1]] = () => load().then((tree) => tree as LocaleMessageTree);
  }
  return out;
}

/**
 * Subscribe to contributed bundles. Called by the host exactly once, at i18n
 * setup; every bundle registered so far is replayed immediately.
 *
 * @returns unsubscribe function.
 */
export function onI18nMessages(sink: MessageSink): () => void {
  const s = store();
  s.sinks.add(sink);
  for (const [id, bundle] of s.bundles) sink(id, bundle, s.lazy?.get(id));
  return () => {
    s.sinks.delete(sink);
  };
}

/** The bundles registered so far — for tests and diagnostics. */
export function registeredI18nBundles(): ReadonlyMap<string, MessageBundle> {
  return store().bundles;
}
