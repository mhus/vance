import { describe, expect, it } from 'vitest';
import { NODE_H, NODE_W } from '@/workflowGraph/layout';
import { GRID, placeNewState } from './placement';

const at = (x: number, y: number) => ({ x, y });

describe('placeNewState', () => {
  it('goes to the top left when nothing stands anywhere', () => {
    expect(placeNewState(new Map())).toEqual({ x: 40, y: 40 });
  });

  it('goes below the state it follows, on the grid', () => {
    const p = placeNewState(new Map([['a', at(100, 100)]]), 'a');
    expect(p.x).toBe(100);
    expect(p.y).toBeGreaterThan(100 + NODE_H);
    expect(p.x % GRID).toBe(0);
    expect(p.y % GRID).toBe(0);
  });

  it('takes the next free place down when something stands below', () => {
    const first = placeNewState(new Map([['a', at(100, 100)]]), 'a');
    const second = placeNewState(new Map([['a', at(100, 100)], ['b', first]]), 'a');
    expect(second.x).toBe(100);
    expect(second.y).toBeGreaterThan(first.y);
  });

  it('goes to the right when the column below is full', () => {
    const map = new Map<string, { x: number; y: number }>([['a', at(100, 100)]]);
    for (let i = 0; i < 40; i++) map.set(`s${i}`, placeNewState(map, 'a'));
    const p = placeNewState(map, 'a');
    expect(p.x).toBeGreaterThanOrEqual(100 + NODE_W);
  });

  it('does not overlap any state', () => {
    const map = new Map([['a', at(0, 0)], ['b', at(0, 140)], ['c', at(0, 280)]]);
    const p = placeNewState(map, 'a');
    for (const o of map.values()) {
      const overlap = p.x < o.x + NODE_W && p.x + NODE_W > o.x && p.y < o.y + NODE_H && p.y + NODE_H > o.y;
      expect(overlap).toBe(false);
    }
  });
});
