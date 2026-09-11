import type { TodoItem } from '@vance/generated';

/**
 * Renders the final content of a closing plan box as the body of the
 * one-time transcript notice (see ChatView's "Plan-box closure notice").
 *
 * Mirrors {@code PlanModeIndicator}'s markers (`[✓]`/`[~]`/`[ ]`) and its
 * activeForm-for-in-progress labelling, so the notice reads exactly like
 * the box it replaces — the notice is the box's last snapshot, not a
 * different rendering of the same data. Pure by design: extracted from
 * ChatView so the marker/label rules are unit-testable (same reasoning
 * as {@code chatActivity.ts}).
 */
export function planClosureContent(todos: TodoItem[], title: string): string {
  const lines = todos.map((item) => {
    const status = (item.status as unknown as string | undefined) ?? 'PENDING';
    const marker = status === 'COMPLETED' ? '[✓]' : status === 'IN_PROGRESS' ? '[~]' : '[ ]';
    const label =
      status === 'IN_PROGRESS' && item.activeForm?.trim() ? item.activeForm : item.content;
    return `- ${marker} ${label}`;
  });
  return `${title}\n${lines.join('\n')}`;
}
