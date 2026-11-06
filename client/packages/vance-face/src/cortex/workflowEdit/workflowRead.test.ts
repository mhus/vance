import { describe, expect, it } from 'vitest';
import { readStateFields } from './workflowRead';

const DOC = `start: a
states:
  a:
    type: agent_task
    description: first
    recipe: jeltz
    params: { prompt: hi }
    on: { success: b }
  b:
    type: terminal
`;

describe('readStateFields', () => {
  it('returns the fields the inspector lists, without type, description and routing', () => {
    expect(readStateFields(DOC, 'a')).toEqual({ recipe: 'jeltz', params: { prompt: 'hi' } });
    expect(readStateFields(DOC, 'b')).toEqual({});
  });

  it('is null for an unknown state or text that does not parse', () => {
    expect(readStateFields(DOC, 'nope')).toBeNull();
    expect(readStateFields('start: [', 'a')).toBeNull();
    expect(readStateFields('', 'a')).toBeNull();
  });
});
