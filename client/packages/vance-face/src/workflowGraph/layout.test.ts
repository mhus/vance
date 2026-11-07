import { describe, expect, it } from 'vitest';
import { fallbackPosition, layoutGraph, layoutPositions, NODE_H, NODE_W } from './layout';
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

const LOOP = `
start: poll
states:
  poll:
    type: tool_task
    tool: x
    on: { success: each }
  each:
    type: for_each_task
    items: "\${state.items}"
    on: { each: work, done: commit }
  work:
    type: shell_task
    run: "true"
    on: { success: step }
  step:
    type: shell_task
    run: "true"
    on: { success: next }
  next:
    type: end
  commit:
    type: tool_task
    tool: y
    on: { success: done }
  done:
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
    type: shell_task
    run: "true"
    on: { success: cell_end }
  cell_end:
    type: end
  row_end:
    type: end
  finish:
    type: terminal
    outcome: success
`;

function inside(frame: { x: number; y: number; width: number; height: number }, node: { x: number; y: number }): boolean {
  return node.x >= frame.x && node.y >= frame.y
    && node.x + NODE_W <= frame.x + frame.width && node.y + NODE_H <= frame.y + frame.height;
}

describe('layoutGraph frames', () => {
  it('has no frame for a workflow without a loop', () => {
    expect(layoutGraph(parseWorkflowGraph(DOC), 'TB').frames).toEqual([]);
  });

  it('puts a frame around the body states and no other state', () => {
    for (const direction of ['TB', 'LR'] as const) {
      const { positions, frames } = layoutGraph(parseWorkflowGraph(LOOP), direction);
      expect(frames).toHaveLength(1);
      const frame = frames[0];
      expect(frame.loop).toBe('each');
      for (const body of ['work', 'step', 'next']) {
        expect(inside(frame, positions.get(body)!), `${direction}: ${body} inside`).toBe(true);
      }
      for (const other of ['poll', 'each', 'commit', 'done']) {
        const p = positions.get(other)!;
        const overlaps = p.x < frame.x + frame.width && p.x + NODE_W > frame.x
          && p.y < frame.y + frame.height && p.y + NODE_H > frame.y;
        expect(overlaps, `${direction}: ${other} outside`).toBe(false);
      }
    }
  });

  it('nests the frame of an inner loop inside the outer one, which stands off further', () => {
    const { frames } = layoutGraph(parseWorkflowGraph(NESTED), 'TB');
    expect(frames.map((f) => f.loop)).toEqual(['outer', 'inner']);
    const [outer, inner] = frames;
    expect(inner.x).toBeGreaterThan(outer.x);
    expect(inner.y).toBeGreaterThan(outer.y);
    expect(inner.x + inner.width).toBeLessThan(outer.x + outer.width);
    expect(inner.y + inner.height).toBeLessThan(outer.y + outer.height);
  });

  it('still places every state, ghosts of undeclared states included', () => {
    const g = parseWorkflowGraph(`
start: a
states:
  a:
    type: timer_task
    on: { done: nowhere }
`);
    expect(layoutGraph(g, 'TB').positions.has('nowhere')).toBe(true);
  });
});
