import { describe, expect, it } from 'vitest';
import {
  connectionKinds,
  defaultLabel,
  ERROR_KINDS,
  outcomeSuggestions,
} from './connectionVocabulary';

describe('connectionKinds', () => {
  it('gives a terminal state nothing, a condition_task its branches, the rest on and catch', () => {
    expect(connectionKinds('terminal')).toEqual([]);
    expect(connectionKinds('condition_task')).toEqual(['condition', 'else', 'catch']);
    expect(connectionKinds('shell_task')).toEqual(['on', 'catch']);
  });
});

describe('outcomeSuggestions', () => {
  it('knows the words of a gate and a loop and nothing of an unknown type', () => {
    expect(outcomeSuggestions('gate_task')).toContain('approved');
    expect(outcomeSuggestions('for_each_task')).toEqual(['each', 'done']);
    expect(outcomeSuggestions('nonsense')).toEqual([]);
  });
});

describe('defaultLabel', () => {
  it('takes the first outcome that is free', () => {
    expect(defaultLabel('on', 'gate_task', [])).toBe('approved');
    expect(defaultLabel('on', 'gate_task', ['approved'])).toBe('rejected');
  });

  it('offers each first and then done for a loop', () => {
    expect(defaultLabel('on', 'for_each_task', [])).toBe('each');
    expect(defaultLabel('on', 'for_each_task', ['each'])).toBe('done');
  });

  it('takes the first error kind that is free', () => {
    expect(defaultLabel('catch', 'shell_task', [])).toBe(ERROR_KINDS[0]);
    expect(defaultLabel('catch', 'shell_task', [ERROR_KINDS[0]])).toBe(ERROR_KINDS[1]);
  });

  it('leaves an else empty, starts a branch on a condition that is always true, and gives up on an empty pool', () => {
    expect(defaultLabel('else', 'condition_task', [])).toBe('');
    expect(defaultLabel('condition', 'condition_task', [])).toBe('true');
    expect(defaultLabel('on', 'nonsense', [])).toBe('');
  });
});
