import type { GraphStateRun } from './overlay';

/**
 * CSS classes of one state node: the structural ones, plus how a run left the
 * state when a run is laid over the drawing. Without a run — the plain view of
 * a definition — there is nothing run-related to say, and nothing may be read
 * from it.
 */
export function nodeClasses(
  state: { isStart: boolean; missing: boolean },
  run?: GraphStateRun,
  breakpoint = false,
): string[] {
  const classes = ['wf-node'];
  if (state.isStart) classes.push('wf-node--start');
  if (state.missing) classes.push('wf-node--missing');
  if (run) classes.push(`wf-node--run-${run.tone}`);
  if (breakpoint) classes.push('wf-node--breakpoint');
  return classes;
}
