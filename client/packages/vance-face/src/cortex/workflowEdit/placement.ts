import { NODE_H, NODE_W, type NodePosition } from '@/workflowGraph/layout';

/** Distance kept between a new state and the ones around it. */
const GAP = 50;
/** Grid the editor snaps to; a new state starts on it. */
export const GRID = 20;

const snap = (n: number): number => Math.round(n / GRID) * GRID;

function overlaps(a: NodePosition, b: NodePosition): boolean {
  return a.x < b.x + NODE_W + GAP / 2 && a.x + NODE_W + GAP / 2 > b.x
    && a.y < b.y + NODE_H + GAP / 2 && a.y + NODE_H + GAP / 2 > b.y;
}

/**
 * Where a state that is made by hand goes: below {@code anchor} (the state it follows) when there is one, else
 * at the top left, and — when something already stands there — the next free place down, then to the right.
 * The editor writes the result at once, so a hand-arranged drawing never has states that were not placed.
 */
export function placeNewState(
  positions: ReadonlyMap<string, NodePosition>,
  anchor?: string,
): NodePosition {
  const from = (anchor ? positions.get(anchor) : undefined) ?? { x: 40, y: 0 };
  const startY = anchor && positions.has(anchor) ? from.y + NODE_H + GAP : 40;
  const others = [...positions.values()];
  for (let column = 0; column < 8; column++) {
    for (let row = 0; row < 40; row++) {
      const at = { x: snap(from.x + column * (NODE_W + GAP)), y: snap(startY + row * (NODE_H + GAP)) };
      if (!others.some((o) => overlaps(at, o))) return at;
    }
  }
  return { x: snap(from.x), y: snap(startY) };
}
