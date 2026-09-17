/**
 * Generic category grouping for picker lists whose entries carry a
 * normalised {@code category} key — the skill panel and the recipe
 * picker share the exact contract (skills.md §4f, recipes.md §6e):
 *
 * - The server sorts the flat list for grouped rendering (documented
 *   categories in document order, then undocumented ones
 *   alphabetically, entries without a category last) — so grouping by
 *   key in first-occurrence order reproduces the intended group order
 *   without a second round trip, and stays correct even if the list
 *   arrives in a different order.
 * - Labels: the category document carries an open locale → text map
 *   that the server never resolves. Resolution order: exact UI locale,
 *   its base language (`de-CH` → `de`), English, then a humanised
 *   category id (`code-read` → `Code Read`). The `otherLabel` (an i18n
 *   string of the host) is used for the null-key group — but only when
 *   categorized groups exist; an ungrouped list renders without
 *   headers.
 */

/** One rendered group: `key` is the category id, or `null` for the trailing "no category" block. */
export interface CategoryGroup<T> {
  key: string | null;
  label: string;
  items: T[];
}

/** Category metadata as shipped by the server — `id` plus the open locale → text map. */
export interface CategoryMeta {
  id: string;
  title?: Record<string, string> | null;
}

/**
 * Groups pre-sorted entries by their category key in first-occurrence
 * order. `categoryOf` reads the key off the entry (the DTOs name the
 * field `category`, but wrapped rows carry it nested).
 */
export function groupCategorized<T>(
  items: T[],
  categoryOf: (item: T) => string | null | undefined,
  categories: CategoryMeta[],
  locale: string,
  otherLabel: string,
): CategoryGroup<T>[] {
  const titles = new Map<string, Record<string, string>>();
  for (const category of categories) {
    titles.set(category.id, category.title ?? {});
  }
  const hasCategorized = items.some((item) => {
    const key = categoryOf(item);
    return key !== null && key !== undefined;
  });

  const groups: CategoryGroup<T>[] = [];
  const byKey = new Map<string | null, CategoryGroup<T>>();
  for (const item of items) {
    const key = categoryOf(item) ?? null;
    let group = byKey.get(key);
    if (!group) {
      group = {
        key,
        label: key === null
          ? (hasCategorized ? otherLabel : '')
          : categoryLabel(key, titles.get(key), locale),
        items: [],
      };
      byKey.set(key, group);
      groups.push(group);
    }
    group.items.push(item);
  }
  return groups;
}

/** Locale → text resolution with base-language and English fallbacks. */
function categoryLabel(
  key: string,
  title: Record<string, string> | undefined,
  locale: string,
): string {
  const candidates = [locale, baseLanguage(locale), 'en'];
  for (const candidate of candidates) {
    const label = title?.[candidate];
    if (label) return label;
  }
  return humanize(key);
}

/** `de-CH` → `de`; a locale without a region part maps to itself. */
function baseLanguage(locale: string): string {
  const idx = locale.indexOf('-');
  return idx === -1 ? locale : locale.slice(0, idx);
}

/** `code-read` → `Code Read` — the only generic label for an open vocabulary. */
function humanize(key: string): string {
  return key
    .split('-')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}
