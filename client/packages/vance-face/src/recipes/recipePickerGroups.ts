import type { RecipeCategoryDto, RecipeListedDto } from '@vance/generated';

/**
 * One rendered group of the recipe-picker modal.
 *
 * `key` is the category id, or `null` for the trailing "no category"
 * block. `label` is the resolved header text; it is empty for a null-key
 * group that is the only group (a plain list needs no "Other" header).
 */
export interface RecipePickerGroup {
  key: string | null;
  label: string;
  recipes: RecipeListedDto[];
}

/**
 * Groups the server-sorted listed recipes for the recipe-picker modal.
 *
 * The server already sorts the flat list for grouped rendering
 * (documented categories in document order, then undocumented ones
 * alphabetically, entries without a category last) — so grouping by key
 * in first-occurrence order reproduces the intended group order without
 * a second round trip, and stays correct even if the list arrives in a
 * different order.
 *
 * Labels: the category document carries an open locale → text map that
 * the server never resolves. Resolution order: exact UI locale, its
 * base language (`de-CH` → `de`), English, then a humanised category id
 * (`code-read` → `Code Read`). The `otherLabel` (an i18n string of the
 * host) is used for the null-key group — but only when categorized
 * groups exist; an ungrouped list renders without headers.
 */
export function groupListedRecipes(
  recipes: RecipeListedDto[],
  categories: RecipeCategoryDto[],
  locale: string,
  otherLabel: string,
): RecipePickerGroup[] {
  const titles = new Map<string, Record<string, string>>();
  for (const category of categories) {
    titles.set(category.id, category.title ?? {});
  }
  const hasCategorized = recipes.some((recipe) => recipe.category !== null && recipe.category !== undefined);

  const groups: RecipePickerGroup[] = [];
  const byKey = new Map<string | null, RecipePickerGroup>();
  for (const recipe of recipes) {
    const key = recipe.category ?? null;
    let group = byKey.get(key);
    if (!group) {
      group = {
        key,
        label: key === null
          ? (hasCategorized ? otherLabel : '')
          : categoryLabel(key, titles.get(key), locale),
        recipes: [],
      };
      byKey.set(key, group);
      groups.push(group);
    }
    group.recipes.push(recipe);
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
