import { RecipeSurface, type RecipeCategoryDto, type RecipeListedDto } from '@vance/generated';
import type { WebUiLevel } from '@/platform';
import { groupCategorized } from '@/util/categoryGroups';

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
 * The shared grouping/label-resolution contract lives in
 * {@link groupCategorized} (skills.md §4f, recipes.md §6e); this is the
 * recipe-typed view over it.
 */
export function groupListedRecipes(
  recipes: RecipeListedDto[],
  categories: RecipeCategoryDto[],
  locale: string,
  otherLabel: string,
): RecipePickerGroup[] {
  return groupCategorized(recipes, (recipe) => recipe.category, categories, locale, otherLabel)
    .map((group) => ({ key: group.key, label: group.label, recipes: group.items }));
}

/**
 * Drops the recipes of a higher surface than the reader chose: an `expert`
 * recipe is not offered on the standard surface. A recipe without a
 * `surface` reads as standard.
 */
export function visibleForLevel(recipes: RecipeListedDto[], level: WebUiLevel): RecipeListedDto[] {
  if (level === 'expert') return recipes;
  return recipes.filter((recipe) => recipe.surface !== RecipeSurface.EXPERT);
}

/**
 * Case-insensitive substring filter over display name and description —
 * the two things a person scanning the picker reads. Grouping happens
 * on the filtered list, so groups without matches never render.
 */
export function filterListedRecipes(
  recipes: RecipeListedDto[],
  needle: string,
): RecipeListedDto[] {
  const query = needle.trim().toLowerCase();
  if (!query) return recipes;
  return recipes.filter((recipe) =>
    (recipe.title || recipe.name).toLowerCase().includes(query)
    || (recipe.description ?? '').toLowerCase().includes(query));
}
