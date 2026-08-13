import { describe, expect, it } from 'vitest';
import { parseWorkflowGraph } from './workflowFlowCodec';

const FULL = `
$meta:
  kind: vance-workflow
description: demo
start: plan
states:
  plan:
    type: agent_task
    recipe: jeltz
    storeAs: plan_output
    retry:
      maxAttempts: 2
    on:
      success: route
    catch:
      agent_error: escalate
  route:
    type: condition_task
    transitions:
      - if: "#state['plan_output']['risk'] == 'low'"
        to: done
      - else: escalate
  done:
    type: terminal
    outcome: success
  escalate:
    type: terminal
    outcome: failure
`;

describe('parseWorkflowGraph', () => {
  it('projects states and outcome edges', () => {
    const g = parseWorkflowGraph(FULL);
    expect(g.problems).toEqual([]);
    expect(g.start).toBe('plan');
    expect(g.states.map((s) => s.name)).toEqual(['plan', 'route', 'done', 'escalate']);
    expect(g.edges.map((e) => `${e.source}-${e.label}->${e.target}`)).toEqual([
      'plan-success->route',
      'plan-agent_error->escalate',
      "route-#state['plan_output']['risk'] == 'low'->done",
      'route-else->escalate',
    ]);
  });

  it('tags each edge with the block it came from', () => {
    const kinds = parseWorkflowGraph(FULL).edges.map((e) => e.kind);
    expect(kinds).toEqual(['on', 'catch', 'condition', 'else']);
  });

  it('carries the start flag and per-type detail', () => {
    const byName = new Map(parseWorkflowGraph(FULL).states.map((s) => [s.name, s]));
    expect(byName.get('plan')?.isStart).toBe(true);
    expect(byName.get('plan')?.detail).toBe('jeltz');
    expect(byName.get('plan')?.hasRetry).toBe(true);
    expect(byName.get('route')?.detail).toBe('2 branch(es)');
    expect(byName.get('done')?.isTerminal).toBe(true);
    expect(byName.get('done')?.terminalOutcome).toBe('success');
  });

  it('keeps `on` as a mapping key instead of the YAML-1.1 boolean', () => {
    // The whole reason the server installs a YAML-1.2 resolver: under
    // 1.1 semantics `on:` parses as `true:` and every transition vanishes.
    const g = parseWorkflowGraph(FULL);
    expect(g.edges.some((e) => e.kind === 'on')).toBe(true);
  });

  it('renders an undeclared target as a ghost node and reports it', () => {
    const g = parseWorkflowGraph(
      'start: a\nstates:\n  a:\n    type: terminal\n    on:\n      success: nowhere\n',
    );
    const ghost = g.states.find((s) => s.name === 'nowhere');
    expect(ghost?.missing).toBe(true);
    expect(g.edges[0].dangling).toBe(true);
    expect(g.problems).toEqual(["state 'a' points to undeclared state 'nowhere'"]);
  });

  it('reports a start that names no declared state', () => {
    const g = parseWorkflowGraph('start: ghost\nstates:\n  a:\n    type: terminal\n');
    expect(g.problems).toEqual(["'start: ghost' does not match any state"]);
  });

  it('reports an unknown task type but still draws the state', () => {
    const g = parseWorkflowGraph('start: a\nstates:\n  a:\n    type: wat\n');
    expect(g.states).toHaveLength(1);
    expect(g.states[0].knownType).toBe(false);
    expect(g.problems).toEqual(["state 'a' has unknown type 'wat'"]);
  });

  it('normalises dashed task types', () => {
    const g = parseWorkflowGraph('start: a\nstates:\n  a:\n    type: Agent-Task\n');
    expect(g.states[0].type).toBe('agent_task');
    expect(g.states[0].knownType).toBe(true);
  });

  it('survives malformed YAML with a problem instead of a throw', () => {
    const g = parseWorkflowGraph('start: a\n  bad indent: [');
    expect(g.states).toEqual([]);
    expect(g.problems).toHaveLength(1);
  });

  it('survives a document with no states block', () => {
    const g = parseWorkflowGraph('start: a\n');
    expect(g.problems).toContain("missing required field 'states'");
  });

  it('surfaces declared parameters in document order', () => {
    const g = parseWorkflowGraph(`
start: a
parameters:
  pr_url: { type: string, required: true }
  reviewer: { type: string, required: false, default: "@maintainers" }
states:
  a:
    type: terminal
`);
    expect(g.parameters).toEqual([
      { name: 'pr_url', type: 'string', required: true, defaultValue: undefined },
      { name: 'reviewer', type: 'string', required: false, defaultValue: '@maintainers' },
    ]);
  });

  it('has no parameters when the block is absent', () => {
    expect(parseWorkflowGraph('start: a\nstates:\n  a:\n    type: terminal\n').parameters)
      .toEqual([]);
  });

  it('reports an empty document', () => {
    expect(parseWorkflowGraph('   ').problems).toEqual(['empty document']);
  });
});
