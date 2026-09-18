/**
 * Session-local undo/redo over the stroke array. The editor owns the array
 * and applies the operations; this class only keeps the log — so the ops are
 * data, not closures.
 *
 * <p>History is never persisted and never crosses a reload: after a remote
 * reload or sheet switch the stack is empty, there is no cross-session undo.
 */
import type { ScribbleStrokeDto } from './generated/scribble/ScribbleStrokeDto';

/**
 * One user-visible edit step. For 'remove' the entries carry the index each
 * stroke had in the array BEFORE the erase began (erasing walks back-to-front,
 * so earlier indices never shift while collecting) — that is what makes both
 * re-application and re-insertion computable from the op alone.
 */
export type StrokeOp =
  | { type: 'add'; strokes: ScribbleStrokeDto[] }
  | { type: 'remove'; entries: { index: number; stroke: ScribbleStrokeDto }[] };

const MAX_OPS = 100;

export class StrokeHistory {
  private undoStack: StrokeOp[] = [];
  private redoStack: StrokeOp[] = [];

  get canUndo(): boolean {
    return this.undoStack.length > 0;
  }

  get canRedo(): boolean {
    return this.redoStack.length > 0;
  }

  /** Record an applied operation; clears the redo stack (new branch). */
  push(op: StrokeOp): void {
    this.undoStack.push(op);
    if (this.undoStack.length > MAX_OPS) this.undoStack.shift();
    this.redoStack = [];
  }

  /** Take the next operation to revert. */
  popUndo(): StrokeOp | null {
    const op = this.undoStack.pop() ?? null;
    if (op) this.redoStack.push(op);
    return op;
  }

  /** Take the next operation to re-apply. */
  popRedo(): StrokeOp | null {
    const op = this.redoStack.pop() ?? null;
    if (op) this.undoStack.push(op);
    return op;
  }

  reset(): void {
    this.undoStack = [];
    this.redoStack = [];
  }
}

/** Apply an operation in the direction the user originally did. */
export function applyOp(strokes: ScribbleStrokeDto[], op: StrokeOp): ScribbleStrokeDto[] {
  if (op.type === 'add') return [...strokes, ...op.strokes];
  const out = [...strokes];
  // Descending: earlier indices stay valid while splicing.
  for (const entry of [...op.entries].sort((a, b) => b.index - a.index)) {
    out.splice(entry.index, 1);
  }
  return out;
}

/** Revert an operation. */
export function revertOp(strokes: ScribbleStrokeDto[], op: StrokeOp): ScribbleStrokeDto[] {
  if (op.type === 'add') return strokes.slice(0, strokes.length - op.strokes.length);
  const out = [...strokes];
  // Re-insert descending. Position formula: the array is missing every stroke
  // this op removed, so the entry with original index i lands at
  // i − (removed strokes with original index < i).
  for (const entry of [...op.entries].sort((a, b) => b.index - a.index)) {
    let below = 0;
    for (const other of op.entries) {
      if (other.index < entry.index) below++;
    }
    out.splice(entry.index - below, 0, entry.stroke);
  }
  return out;
}
