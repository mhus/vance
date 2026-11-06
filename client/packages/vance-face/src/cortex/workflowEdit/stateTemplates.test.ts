import { describe, expect, it } from 'vitest';
import { WORKFLOW_TASK_TYPES } from '@/workflowGraph/workflowGraphCodec';
import { addState } from './workflowMutations';
import { stateTemplate, TEMPLATE_TYPES } from './stateTemplates';
import { parseWorkflowGraph } from '@/workflowGraph/workflowGraphCodec';

describe('stateTemplate', () => {
  it('has a template for every task type, carrying that type', () => {
    expect([...TEMPLATE_TYPES]).toEqual([...WORKFLOW_TASK_TYPES]);
    for (const type of WORKFLOW_TASK_TYPES) {
      expect(stateTemplate(type).type).toBe(type);
    }
  });

  it('gives a copy each time', () => {
    const a = stateTemplate('agent_task');
    (a.params as Record<string, unknown>).prompt = 'changed';
    expect((stateTemplate('agent_task').params as Record<string, unknown>).prompt).toBe(
      'Describe the task.',
    );
  });

  it('makes a workflow the drawing reads without problems, whatever the type', () => {
    for (const type of WORKFLOW_TASK_TYPES) {
      const g = parseWorkflowGraph(addState('', 'first', stateTemplate(type)));
      expect(g.problems, type).toEqual([]);
      expect(g.states[0].knownType, type).toBe(true);
    }
  });
});
