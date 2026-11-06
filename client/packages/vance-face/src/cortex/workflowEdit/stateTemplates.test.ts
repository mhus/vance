import { reactive } from 'vue';
import { describe, expect, it } from 'vitest';
import { WORKFLOW_TASK_TYPES } from '@/workflowGraph/workflowGraphCodec';
import { addState } from './workflowMutations';
import { stateTemplate, TEMPLATE_TYPES, toolStateTemplate } from './stateTemplates';
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
      expect(g.states[0].knownType, type).toBe(true);
      // A loop is only complete with its two arrows; nothing else is missing from a new state.
      const expected = type === 'for_each_task'
        ? ["state 'first' (for_each_task) needs 'on: each: <state>'", "state 'first' (for_each_task) needs 'on: done: <state>'"]
        : [];
      expect(g.problems, type).toEqual(expected);
    }
  });
});

describe('toolStateTemplate', () => {
  it('names the tool and carries the example parameters', () => {
    const body = toolStateTemplate('slack_rest__chat_post_message', { channel: 'C1' });
    expect(body).toEqual({ type: 'tool_task', tool: 'slack_rest__chat_post_message', params: { channel: 'C1' } });
  });

  it('takes a reactive example, which cannot be structured-cloned, into a document', () => {
    const body = toolStateTemplate('slack_rest__x', reactive({ channel: 'C1', nested: { a: 1 } }));
    const g = parseWorkflowGraph(addState('', 'x', body));
    expect(g.problems).toEqual([]);
    expect(addState('', 'x', body)).toMatch(/params:\n\s+channel: C1\n\s+nested:\n\s+a: 1/);
  });

  it('leaves params out when there is no example', () => {
    expect(toolStateTemplate('doc_read')).toEqual({ type: 'tool_task', tool: 'doc_read' });
    expect(toolStateTemplate('doc_read', {})).toEqual({ type: 'tool_task', tool: 'doc_read' });
  });
});
