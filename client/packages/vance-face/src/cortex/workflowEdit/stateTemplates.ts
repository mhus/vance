import { WORKFLOW_TASK_TYPES, type WorkflowTaskType } from '@/workflowGraph/workflowGraphCodec';

/**
 * What a new state of each task type starts with: the type and the one field
 * that names what it does, filled with a value the author replaces. Enough
 * for the server to load the workflow; nothing here is a default the server
 * would apply on its own.
 *
 * Spec: {@code specification/public/workflows.md} §3.
 */
const TEMPLATES: Record<WorkflowTaskType, Record<string, unknown>> = {
  agent_task: { type: 'agent_task', recipe: 'ford', params: { prompt: 'Describe the task.' } },
  shell_task: { type: 'shell_task', run: 'echo hello' },
  script_task: { type: 'script_task', path: 'scripts/example.js' },
  tool_task: { type: 'tool_task', tool: 'tool_name' },
  gate_task: { type: 'gate_task', inbox: { kind: 'APPROVAL', title: 'Approve?' } },
  timer_task: { type: 'timer_task', duration: '1h' },
  condition_task: { type: 'condition_task' },
  workflow_task: { type: 'workflow_task', workflow: 'other-workflow' },
  terminal: { type: 'terminal', outcome: 'success' },
};

/** The task types in the order a menu lists them. */
export const TEMPLATE_TYPES: readonly WorkflowTaskType[] = WORKFLOW_TASK_TYPES;

/** A fresh copy of the starting body of a state of {@code type}. */
export function stateTemplate(type: WorkflowTaskType): Record<string, unknown> {
  return structuredClone(TEMPLATES[type]);
}
