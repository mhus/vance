import { describe, expect, it } from 'vitest';
import { StrokeHistory, applyOp, revertOp } from './history';
import type { ScribbleStrokeDto } from './generated/scribble/ScribbleStrokeDto';

const label = new WeakMap<ScribbleStrokeDto, string>();

/** A minimal stroke — the history only ever looks at array identity/order. */
function stroke(name: string): ScribbleStrokeDto {
  const s: ScribbleStrokeDto = { tool: 'pen', color: '1', width: 'm', points: [[1, 1, 0.5]] };
  label.set(s, name);
  return s;
}

function names(strokes: ScribbleStrokeDto[]): string[] {
  return strokes.map((s) => label.get(s) ?? '?');
}

/**
 * Regression: the first version of applyOp/revertOp had the remove direction
 * swapped (undo of an erase deleted again, redo restored) and re-inserted at
 * naive indices — an erase of C and E in [A,B,C,D,E] came back as
 * [A,B,C,D,D]. These cases pin both the directions and the position formula.
 */
describe('StrokeHistory apply/revert', () => {
  it('undo of a multi-stroke erase restores every stroke at its position', () => {
    const a = stroke('A');
    const b = stroke('B');
    const c = stroke('C');
    const d = stroke('D');
    const e = stroke('E');
    // Erase gesture removed C(2) and E(4) back-to-front.
    const op = {
      type: 'remove' as const,
      entries: [
        { index: 4, stroke: e },
        { index: 2, stroke: c },
      ],
    };
    const after = [a, b, d];

    const restored = revertOp(after, op);
    expect(names(restored)).toEqual(['A', 'B', 'C', 'D', 'E']);
  });

  it('redo of the same erase removes exactly those strokes again', () => {
    const a = stroke('A');
    const b = stroke('B');
    const c = stroke('C');
    const d = stroke('D');
    const e = stroke('E');
    const op = {
      type: 'remove' as const,
      entries: [
        { index: 4, stroke: e },
        { index: 2, stroke: c },
      ],
    };
    const full = [a, b, c, d, e];

    expect(names(applyOp(full, op))).toEqual(['A', 'B', 'D']);
  });

  it('erase with adjacent and leading indices round-trips positions', () => {
    const strokes = ['A', 'B', 'C', 'D'].map(stroke);
    // Removed A(0) and C(2).
    const op = {
      type: 'remove' as const,
      entries: [
        { index: 2, stroke: strokes[2] },
        { index: 0, stroke: strokes[0] },
      ],
    };
    const after = [strokes[1], strokes[3]];
    expect(names(after)).toEqual(['B', 'D']);

    const restored = revertOp(after, op);
    expect(names(restored)).toEqual(['A', 'B', 'C', 'D']);
    expect(names(applyOp(restored, op))).toEqual(['B', 'D']);
  });

  it('add ops append on apply and cut the tail on revert', () => {
    const base = [stroke('A')];
    const s1 = stroke('S1');
    const s2 = stroke('S2');
    const op = { type: 'add' as const, strokes: [s1, s2] };

    const grown = applyOp(base, op);
    expect(names(grown)).toEqual(['A', 'S1', 'S2']);
    expect(names(revertOp(grown, op))).toEqual(['A']);
  });
});

describe('StrokeHistory stack', () => {
  it('push clears the redo stack (new branch)', () => {
    const h = new StrokeHistory();
    const s1 = stroke('S1');
    const s2 = stroke('S2');
    h.push({ type: 'add', strokes: [s1] });

    const undone = h.popUndo();
    expect(undone).not.toBeNull();
    expect(h.canRedo).toBe(true);

    // A fresh edit while a redo exists must discard the redo branch.
    h.push({ type: 'add', strokes: [s2] });
    expect(h.canRedo).toBe(false);
    expect(h.canUndo).toBe(true);
  });

  it('popUndo/popRedo cycle the same op through both stacks', () => {
    const h = new StrokeHistory();
    const op = { type: 'add' as const, strokes: [stroke('S1')] };
    h.push(op);

    expect(h.popUndo()).toBe(op);
    expect(h.canUndo).toBe(false);
    expect(h.popRedo()).toBe(op);
    expect(h.canRedo).toBe(false);
    expect(h.canUndo).toBe(true);
  });

  it('reset empties both stacks', () => {
    const h = new StrokeHistory();
    h.push({ type: 'add', strokes: [stroke('S1')] });
    h.reset();
    expect(h.canUndo).toBe(false);
    expect(h.canRedo).toBe(false);
    expect(h.popUndo()).toBeNull();
  });
});
