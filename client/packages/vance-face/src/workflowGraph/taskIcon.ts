import type { IconName } from '@/components';
import type { WorkflowTaskType } from './workflowGraphCodec';

const BY_TYPE: Record<WorkflowTaskType, IconName> = {
  agent_task: 'task-agent',
  shell_task: 'task-shell',
  script_task: 'task-script',
  tool_task: 'task-tool',
  gate_task: 'task-gate',
  timer_task: 'task-timer',
  condition_task: 'task-condition',
  workflow_task: 'task-workflow',
  for_each_task: 'task-for-each',
  end: 'task-end',
  terminal: 'task-terminal',
};

/** The icon of a task type, or {@code null} for a type the editor does not know (a state with a typo, a ghost). */
export function taskIcon(type: string): IconName | null {
  return (BY_TYPE as Record<string, IconName | undefined>)[type] ?? null;
}
