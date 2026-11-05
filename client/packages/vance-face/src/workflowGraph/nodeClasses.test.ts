import { describe, expect, it } from 'vitest';
import { nodeClasses } from './nodeClasses';

describe('nodeClasses', () => {
  it('draws a plain definition node without reading a run', () => {
    // The Cortex view has no overlay: `run` is undefined for every node.
    expect(nodeClasses({ isStart: false, missing: false }, undefined)).toEqual(['wf-node']);
  });

  it('marks the start and a missing state', () => {
    expect(nodeClasses({ isStart: true, missing: true })).toEqual([
      'wf-node', 'wf-node--start', 'wf-node--missing',
    ]);
  });

  it('adds the tone of a run that reached the state', () => {
    expect(nodeClasses({ isStart: false, missing: false }, { tone: 'halted', entries: 1 }))
      .toEqual(['wf-node', 'wf-node--run-halted']);
  });
});
