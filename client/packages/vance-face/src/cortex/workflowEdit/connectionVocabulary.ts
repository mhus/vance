import type { WorkflowEdgeKind } from '@/workflowGraph/workflowGraphCodec';

/**
 * What a connection out of a state can be called, per task type: the words the
 * editor offers, never the words it enforces — an outcome is free text (a
 * decision's option, an agent's {@code decide:} reading), and the server stays
 * the judge.
 *
 * Spec: {@code specification/public/workflows.md} §3, §4.1, §4.2.
 */

/** The error kinds {@code catch:} understands (§4.2). */
export const ERROR_KINDS = [
  'technical_error',
  'business_error',
  'agent_error',
  'timeout',
  'permission_error',
  'human_rejected',
  'cancelled',
  'rate_limited',
  'auth_error',
  'not_found',
  'unavailable',
] as const;

const OUTCOMES: Record<string, readonly string[]> = {
  agent_task: ['success', 'needs_input'],
  shell_task: ['success'],
  script_task: ['success'],
  tool_task: ['success'],
  gate_task: ['approved', 'rejected', 'insufficient_info', 'undecidable', 'success'],
  timer_task: ['fired'],
  workflow_task: ['success', 'failure'],
};

/** The outcomes worth offering for {@code on:} of a state of {@code type}. */
export function outcomeSuggestions(type: string): readonly string[] {
  return OUTCOMES[type] ?? [];
}

/**
 * The kinds of connection a state of {@code type} can have. A terminal state
 * has none; only a {@code condition_task} branches ({@code transitions:}).
 */
export function connectionKinds(type: string): WorkflowEdgeKind[] {
  if (type === 'terminal') return [];
  if (type === 'condition_task') return ['condition', 'else', 'catch'];
  return ['on', 'catch'];
}

/**
 * A label for a new connection that is not taken yet: the first suggestion of
 * its kind that the state does not use, else empty (the person types one).
 */
export function defaultLabel(
  kind: WorkflowEdgeKind,
  type: string,
  taken: readonly string[],
): string {
  if (kind === 'else') return '';
  if (kind === 'condition') return 'true';
  const pool: readonly string[] = kind === 'catch' ? ERROR_KINDS : outcomeSuggestions(type);
  return pool.find((label) => !taken.includes(label)) ?? '';
}
