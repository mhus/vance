import { describe, expect, it } from 'vitest';
import { ICONS } from '@vance/components';
import { taskIcon } from './taskIcon';
import { WORKFLOW_TASK_TYPES } from './workflowGraphCodec';

describe('taskIcon', () => {
  it('gives every task type an icon of the UI vocabulary', () => {
    for (const type of WORKFLOW_TASK_TYPES) {
      const icon = taskIcon(type);
      expect(icon, type).not.toBeNull();
      expect(ICONS, type).toHaveProperty(icon as string);
    }
  });

  it('gives every task type its own icon', () => {
    const icons = WORKFLOW_TASK_TYPES.map((type) => taskIcon(type));
    expect(new Set(icons).size).toBe(WORKFLOW_TASK_TYPES.length);
  });

  it('has none for a type it does not know', () => {
    expect(taskIcon('')).toBeNull();
    expect(taskIcon('agent_taks')).toBeNull();
  });
});
