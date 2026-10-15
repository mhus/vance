/**
 * In-flight locale loads of addon bundles, so the place that loads an addon's
 * federation expose can wait for its translations before anything renders.
 *
 * <p>An addon registers its messages as an import side effect of its expose:
 * English arrives with the registration, the active locale's json only
 * afterwards (it is fetched on demand). Without waiting, the first view of a
 * lazily loaded addon would paint in English and flip a moment later.
 *
 * <p>Kept apart from `./index` on purpose: the addon loaders under `platform/`
 * need only this, not the i18n instance (and its import of `@/platform`).
 */

const pending = new Set<Promise<void>>();

/** Record a locale load an addon registration started. It must not reject. */
export function trackAddonLocaleLoad(load: Promise<void>): void {
  pending.add(load);
  void load.finally(() => pending.delete(load));
}

/**
 * Resolves once every addon locale load started so far has settled. Await it
 * right after loading an addon expose and before rendering what it provides.
 */
export async function addonLocalesSettled(): Promise<void> {
  await Promise.all([...pending]);
}
