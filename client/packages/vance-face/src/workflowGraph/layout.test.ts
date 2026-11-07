import { describe, expect, it } from 'vitest';
import { fallbackPosition, isManualLayout, layoutGraph, layoutPositions, NODE_H, NODE_W } from './layout';
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

describe('layoutGraph with a layout block', () => {
  const withLayout = (layout: string): string => `${LOOP}\nlayout:\n${layout}`;

  it('is automatic without a block and manual with one', () => {
    expect(isManualLayout(parseWorkflowGraph(LOOP))).toBe(false);
    expect(isManualLayout(parseWorkflowGraph(withLayout('  poll: [10, 20]')))).toBe(true);
  });

  it('keeps every state where it was put, when all are placed', () => {
    const at: Record<string, [number, number]> = {
      poll: [0, 0], each: [0, 200], work: [400, 100], step: [400, 300], next: [400, 500], commit: [0, 400], done: [0, 600],
    };
    const doc = withLayout(Object.entries(at).map(([n, [x, y]]) => `  ${n}: [${x}, ${y}]`).join('\n'));
    const { positions } = layoutGraph(parseWorkflowGraph(doc), 'TB');
    for (const [name, [x, y]] of Object.entries(at)) expect(positions.get(name)).toEqual({ x, y });
  });

  it('places a state the block leaves out automatically, and keeps the others', () => {
    const { positions } = layoutGraph(parseWorkflowGraph(withLayout('  poll: [5, 7]')), 'TB');
    expect(positions.get('poll')).toEqual({ x: 5, y: 7 });
    expect(positions.has('commit')).toBe(true);
  });

  it('skips an entry that is no pair of numbers', () => {
    const g = parseWorkflowGraph(withLayout('  poll: [1]\n  each: [a, b]\n  work: [3, 4]'));
    expect(g.layout).toEqual({ work: { x: 3, y: 4 } });
  });

  it('draws the frame around the body where its states stand', () => {
    const doc = withLayout(
      '  poll: [0, 0]\n  each: [0, 200]\n  work: [600, 100]\n  step: [600, 300]\n  next: [600, 500]\n  commit: [0, 400]\n  done: [0, 600]',
    );
    const { frames } = layoutGraph(parseWorkflowGraph(doc), 'TB');
    expect(frames).toHaveLength(1);
    expect(frames[0].x).toBeLessThan(600);
    expect(frames[0].x + frames[0].width).toBeGreaterThan(600 + NODE_W);
    expect(frames[0].y).toBeLessThan(100);
    expect(frames[0].y + frames[0].height).toBeGreaterThan(500 + NODE_H);
    // The states of the body lie inside it, the others do not.
    expect(frames[0].x).toBeGreaterThan(NODE_W);
  });
});
