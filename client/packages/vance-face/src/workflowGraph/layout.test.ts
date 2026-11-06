import { describe, expect, it } from 'vitest';
import { fallbackPosition, layoutPositions, NODE_H } from './layout';
import { parseWorkflowGraph } from './workflowGraphCodec';

const DOC = `
start: a
states:
  a:
    type: timer_task
    on:
      done: b
  b:
    type: terminal
`;

describe('layoutPositions', () => {
  it('places every declared state, the start above its successor top-down', () => {
    const pos = layoutPositions(parseWorkflowGraph(DOC), 'TB');
    expect([...pos.keys()].sort()).toEqual(['a', 'b']);
    expect(pos.get('b')!.y).toBeGreaterThan(pos.get('a')!.y);
  });

  it('lays left-right when asked', () => {
    const pos = layoutPositions(parseWorkflowGraph(DOC), 'LR');
    expect(pos.get('b')!.x).toBeGreaterThan(pos.get('a')!.x);
  });

  it('returns nothing for an empty graph', () => {
    expect(layoutPositions(parseWorkflowGraph(''), 'TB').size).toBe(0);
  });
});

describe('fallbackPosition', () => {
  it('stacks nodes down the left edge', () => {
    expect(fallbackPosition(1).y).toBeGreaterThan(fallbackPosition(0).y + NODE_H);
    expect(fallbackPosition(1).x).toBe(fallbackPosition(0).x);
  });
});
