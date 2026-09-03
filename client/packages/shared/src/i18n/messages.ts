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
 * <p>Platform-neutral on purpose (no Vue, no vue-i18n): this package is the one
 * place both ends of the federation boundary can agree on.
 */

/** One locale's message tree, in vue-i18n's nested shape. */
export type LocaleMessageTree = Record<string, unknown>;

/** A bundle as an addon ships it: locale code → message tree. */
export type MessageBundle = Record<string, LocaleMessageTree>;

type MessageSink = (id: string, bundle: MessageBundle) => void;

interface Registry {
  /** Bundle id (addon id) → its messages. Re-registering replaces, HMR-friendly. */
  bundles: Map<string, MessageBundle>;
  sinks: Set<MessageSink>;
}

declare global {
  // eslint-disable-next-line no-var
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
 * @param bundle locale code → message tree. Merged into the host's messages per
 *               locale, so an addon may extend a host namespace (a
 *               {@code tabLabelKey} under `documents.detail`) as well as own its
 *               own (`canvas.*`). Locales the host does not support are ignored
 *               by the host, not here.
 */
export function registerI18nMessages(id: string, bundle: MessageBundle): void {
  const s = store();
  s.bundles.set(id, bundle);
  for (const sink of s.sinks) sink(id, bundle);
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
  for (const [id, bundle] of s.bundles) sink(id, bundle);
  return () => {
    s.sinks.delete(sink);
  };
}

/** The bundles registered so far — for tests and diagnostics. */
export function registeredI18nBundles(): ReadonlyMap<string, MessageBundle> {
  return store().bundles;
}
