import { describe, expect, it } from 'vitest';
import { WORKFLOW_TASK_TYPES } from '@/workflowGraph/workflowGraphCodec';
import { fieldSpecsFor, fromDraft, ownedKeys, toDraft, valueAt } from './stateFieldSpecs';

describe('fieldSpecsFor', () => {
  it('has fields for every known type and none for an unknown one', () => {
    for (const type of WORKFLOW_TASK_TYPES) expect(fieldSpecsFor(type).length, type).toBeGreaterThan(0);
    expect(fieldSpecsFor('nonsense')).toEqual([]);
  });

  it('names a required field per type that cannot run without it', () => {
    const required = (type: string) => fieldSpecsFor(type).filter((f) => f.required).map((f) => f.key);
    expect(required('agent_task')).toEqual(['recipe']);
    expect(required('shell_task')).toEqual(['run']);
    expect(required('tool_task')).toEqual(['tool']);
    expect(required('workflow_task')).toEqual(['workflow']);
    expect(required('for_each_task')).toEqual(['items']);
    expect(required('terminal')).toEqual([]);
  });

  it('gives every field of a type a distinct key', () => {
    for (const type of WORKFLOW_TASK_TYPES) {
      const keys = fieldSpecsFor(type).map((f) => f.key);
      expect(new Set(keys).size, type).toBe(keys.length);
    }
  });
});

describe('ownedKeys', () => {
  it('holds the structural keys and the top-level key of each field', () => {
    const owned = ownedKeys('agent_task');
    for (const key of ['type', 'description', 'on', 'catch', 'transitions', 'recipe', 'params', 'storeAs']) {
      expect(owned.has(key), key).toBe(true);
    }
    expect(owned.has('retry')).toBe(false);
    expect(ownedKeys('gate_task').has('inbox')).toBe(true);
    expect(ownedKeys('tool_task').has('params')).toBe(true);
  });
});

describe('valueAt', () => {
  it('reads a nested path and gives undefined where the path breaks', () => {
    const body = { inbox: { kind: 'APPROVAL' }, list: [1] };
    expect(valueAt(body, ['inbox', 'kind'])).toBe('APPROVAL');
    expect(valueAt(body, ['inbox', 'title'])).toBeUndefined();
    expect(valueAt(body, ['list', '0'])).toBeUndefined();
    expect(valueAt(body, ['nope', 'x'])).toBeUndefined();
  });
});

describe('toDraft / fromDraft', () => {
  it('round-trips a list through comma-separated text', () => {
    expect(toDraft('list', ['a', 'b'])).toBe('a, b');
    expect(fromDraft('list', 'a, b ,, c')).toEqual(['a', 'b', 'c']);
    expect(fromDraft('list', ' , ')).toBeUndefined();
  });

  it('reads an integer, refuses anything else, and treats empty as removal', () => {
    expect(fromDraft('integer', ' 600 ')).toBe(600);
    expect(fromDraft('integer', '1.5')).toBe('invalid');
    expect(fromDraft('integer', '-3')).toBe('invalid');
    expect(fromDraft('integer', 'abc')).toBe('invalid');
    expect(fromDraft('integer', '')).toBeUndefined();
  });

  it('keeps the line breaks of a text and trims a string', () => {
    expect(fromDraft('text', 'a\nb  \n')).toBe('a\nb');
    expect(fromDraft('string', '  x ')).toBe('x');
    expect(fromDraft('select', '')).toBeUndefined();
  });

  it('shows nothing for a missing value and text for a stored one', () => {
    expect(toDraft('string', undefined)).toBe('');
    expect(toDraft('integer', 600)).toBe('600');
    expect(toDraft('string', { a: 1 })).toBe('{"a":1}');
  });
});
