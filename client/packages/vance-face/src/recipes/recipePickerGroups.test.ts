import { describe, expect, it } from 'vitest';
import type { RecipeCategoryDto, RecipeListedDto } from '@vance/generated';
import { filterListedRecipes, groupListedRecipes } from './recipePickerGroups';

function recipe(name: string, category: string | null): RecipeListedDto {
  return { name, category: category ?? undefined };
}

function category(id: string, title?: Record<string, string>): RecipeCategoryDto {
  return { id, title };
}

describe('groupListedRecipes', () => {
  it('groups by category in first-occurrence order and appends the other group last', () => {
    const recipes = [
      recipe('arthur', 'chat'),
      recipe('eddie', 'chat'),
      recipe('coding', 'coding'),
      recipe('analyze', 'research'),
      recipe('legacy', null),
    ];

    const groups = groupListedRecipes(recipes, [], 'en', 'Other');

    expect(groups.map((g) => g.key)).toEqual(['chat', 'coding', 'research', null]);
    expect(groups[0].recipes.map((r) => r.name)).toEqual(['arthur', 'eddie']);
    expect(groups[3].label).toBe('Other');
  });

  it('resolves the label from the category title map by UI locale', () => {
    const categories = [
      category('coding', { en: 'Coding', de: 'Programmierung' }),
    ];

    const groups = groupListedRecipes(
      [recipe('coding', 'coding')],
      categories,
      'de',
      'Other',
    );

    expect(groups[0].label).toBe('Programmierung');
  });

  it('falls back to the base language, then english, then a humanised id', () => {
    const titled = category('code-review', { en: 'Code Review' });

    expect(groupListedRecipes([recipe('r', 'code-review')], [titled], 'de-CH', 'Other')[0].label)
      .toBe('Code Review');
    expect(groupListedRecipes([recipe('r', 'code-review')], [], 'de', 'Other')[0].label)
      .toBe('Code Review');
    expect(groupListedRecipes([recipe('r', 'unknown-cat')], [], 'de', 'Other')[0].label)
      .toBe('Unknown Cat');
  });

  it('renders a fully uncategorized list as one group without a header', () => {
    const groups = groupListedRecipes(
      [recipe('a', null), recipe('b', null)],
      [],
      'en',
      'Other',
    );

    expect(groups).toHaveLength(1);
    expect(groups[0].key).toBeNull();
    expect(groups[0].label).toBe('');
    expect(groups[0].recipes).toHaveLength(2);
  });

  it('returns no groups for an empty recipe list', () => {
    expect(groupListedRecipes([], [category('chat')], 'en', 'Other')).toEqual([]);
  });

  it('filters by display name case-insensitively', () => {
    const recipes = [recipe('arthur', 'chat'), recipe('code-read', 'coding')];

    expect(filterListedRecipes(recipes, '  READ ').map((r) => r.name))
      .toEqual(['code-read']);
  });

  it('matches the description too, so keywords find recipes', () => {
    const described = { ...recipe('web-research', 'research'), description: 'Public-web research' };

    expect(filterListedRecipes([recipe('arthur', 'chat'), described], 'public'))
      .toEqual([described]);
  });

  it('returns the unfiltered list for a blank needle', () => {
    const recipes = [recipe('arthur', 'chat')];

    expect(filterListedRecipes(recipes, '   ')).toBe(recipes);
  });
});
