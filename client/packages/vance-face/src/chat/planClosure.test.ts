import { describe, expect, it } from 'vitest';
import type { TodoItem } from '@vance/generated';
import { planClosureContent } from './planClosure';

function item(status: string, content: string, activeForm?: string): TodoItem {
  // The wire carries Jackson's enum *names* — see PlanModeIndicator's
  // boundary note. The generated TS enum is numeric, so the tests (and
  // the production code) treat the status as a string literal.
  return {
    id: '1',
    status: status as unknown as TodoItem['status'],
    content,
    activeForm,
  } as TodoItem;
}

describe('planClosureContent', () => {
  it('renders one marker line per item, mirroring the box markers', () => {
    const todos = [
      item('COMPLETED', 'Migrate token storage'),
      item('IN_PROGRESS', 'Migrate token storage'),
      item('PENDING', 'Run the regression suite'),
    ];

    expect(planClosureContent(todos, 'Plan box closed — final state:')).toBe(
      [
        'Plan box closed — final state:',
        '- [✓] Migrate token storage',
        '- [~] Migrate token storage',
        '- [ ] Run the regression suite',
      ].join('\n'),
    );
  });

  it('prefers activeForm for in-progress items — like the box', () => {
    const todos = [item('IN_PROGRESS', 'Migrate token storage', 'Migrating token storage')];

    expect(planClosureContent(todos, 'Title')).toContain('- [~] Migrating token storage');
  });

  it('treats a blank activeForm as absent', () => {
    const todos = [item('IN_PROGRESS', 'Migrate token storage', '  ')];

    expect(planClosureContent(todos, 'Title')).toContain('- [~] Migrate token storage');
  });

  it('defaults an absent status to PENDING', () => {
    const todos = [{ id: '1', content: 'Untouched' } as TodoItem];

    expect(planClosureContent(todos, 'Title')).toContain('- [ ] Untouched');
  });
});
