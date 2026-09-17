import { describe, expect, it } from 'vitest';
import { groupCategorized } from './categoryGroups';

interface Row {
  summary: { name: string; category?: string };
  active: boolean;
}

function row(name: string, category: string | null, active = false): Row {
  return { summary: { name, category: category ?? undefined }, active };
}

/**
 * The skill panel groups wrapped rows whose category key is nested on
 * the summary — the `categoryOf` accessor exists exactly for that
 * (skills.md §4f). The flat-list contract itself is covered by the
 * recipe-picker tests over the same shared code.
 */
describe('groupCategorized', () => {
  it('groups wrapped rows through the category accessor, other group last', () => {
    const rows = [
      row('code-review', 'coding'),
      row('decision-frame', 'decisions', true),
      row('plain-skill', null),
    ];

    const groups = groupCategorized(rows, (r) => r.summary.category, [], 'en', 'Other');

    expect(groups.map((g) => g.key)).toEqual(['coding', 'decisions', null]);
    expect(groups[0].items[0].summary.name).toBe('code-review');
    expect(groups[1].label).toBe('Decisions');
    expect(groups[2].label).toBe('Other');
  });

  it('renders a fully uncategorized list as one group without a header', () => {
    const groups = groupCategorized([row('a', null)], (r) => r.summary.category, [], 'en', 'Other');

    expect(groups).toHaveLength(1);
    expect(groups[0].key).toBeNull();
    expect(groups[0].label).toBe('');
  });
});
