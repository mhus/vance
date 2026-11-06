import { describe, expect, it } from 'vitest';
import { enclosingLoop, loopBody } from './loopBody';
import { parseWorkflowGraph } from './workflowGraphCodec';

// The same workflows as the server's MagratheaLoopBodyTest: the two have to agree.

const SIMPLE = `
start: loop
states:
  loop:
    type: for_each_task
    items: "\${state.list}"
    on: { each: work, done: report }
  work:
    type: condition_task
    transitions:
      - else: next
  next:
    type: end
  report:
    type: terminal
    outcome: success
`;

const NESTED = `
start: outer
states:
  outer:
    type: for_each_task
    items: "\${state.rows}"
    on: { each: inner, done: finish }
  inner:
    type: for_each_task
    items: "\${state.row}"
    on: { each: cell, done: row_end }
  cell:
    type: condition_task
    transitions:
      - else: cell_end
  cell_end:
    type: end
  row_end:
    type: end
  finish:
    type: terminal
    outcome: success
`;

const SHARED = `
start: a
states:
  a:
    type: for_each_task
    items: "\${state.x}"
    on: { each: a1, done: b }
  a1:
    type: condition_task
    transitions:
      - else: shared
  b:
    type: for_each_task
    items: "\${state.y}"
    on: { each: b1, done: finish }
  b1:
    type: condition_task
    transitions:
      - else: shared
  shared:
    type: end
  finish:
    type: terminal
    outcome: success
`;

describe('loopBody', () => {
  it('is what each reaches before the loop, without what comes after done', () => {
    const g = parseWorkflowGraph(SIMPLE);
    expect([...loopBody(g.states, g.edges, 'loop')]).toEqual(['work', 'next']);
  });

  it('is empty for a state that is no loop and for a loop without an each arrow', () => {
    const g = parseWorkflowGraph(SIMPLE);
    expect(loopBody(g.states, g.edges, 'work').size).toBe(0);
    expect(loopBody(g.states, g.edges, 'nope').size).toBe(0);
  });

  it('keeps an inner body inside the outer one', () => {
    const g = parseWorkflowGraph(NESTED);
    expect([...loopBody(g.states, g.edges, 'inner')]).toEqual(['cell', 'cell_end']);
    expect(new Set(loopBody(g.states, g.edges, 'outer'))).toEqual(
      new Set(['inner', 'cell', 'cell_end', 'row_end']),
    );
  });
});

describe('enclosingLoop', () => {
  it('finds the loop whose body holds the state and none for a state outside', () => {
    const g = parseWorkflowGraph(SIMPLE);
    expect(enclosingLoop(g.states, g.edges, 'next')).toEqual({ kind: 'loop', loop: 'loop' });
    expect(enclosingLoop(g.states, g.edges, 'report')).toEqual({ kind: 'none' });
  });

  it('takes the innermost loop, and the outer one for an end reached through the inner done', () => {
    const g = parseWorkflowGraph(NESTED);
    expect(enclosingLoop(g.states, g.edges, 'cell_end')).toEqual({ kind: 'loop', loop: 'inner' });
    expect(enclosingLoop(g.states, g.edges, 'row_end')).toEqual({ kind: 'loop', loop: 'outer' });
  });

  it('calls two loops that do not lie one inside the other ambiguous', () => {
    const g = parseWorkflowGraph(SHARED);
    expect(enclosingLoop(g.states, g.edges, 'shared').kind).toBe('ambiguous');
  });
});

describe('the drawing of an end state', () => {
  it('names the loop it ends and lists no problem', () => {
    const g = parseWorkflowGraph(SIMPLE);
    expect(g.states.find((s) => s.name === 'next')?.detail).toBe('↺ loop');
    expect(g.problems).toEqual([]);
  });

  it('has no way on: it is drawn like a terminal', () => {
    const g = parseWorkflowGraph(SIMPLE);
    expect(g.states.find((s) => s.name === 'next')?.isTerminal).toBe(true);
  });

  it('lists a problem for an end outside every loop body and for an ambiguous one', () => {
    const outside = parseWorkflowGraph('start: stop\nstates:\n  stop:\n    type: end\n');
    expect(outside.problems).toContain("state 'stop' (end) is not in the body of a for_each_task");
    const shared = parseWorkflowGraph(SHARED);
    expect(shared.problems.join(' ')).toContain('do not lie one inside the other');
  });
});
