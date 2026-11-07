import { describe, expect, it } from 'vitest';
import { filterFields, outputFields, placeholderFor } from './toolOutputFields';

const REST = {
  type: 'object',
  properties: {
    status: { type: 'integer' },
    ok: { type: 'boolean' },
    headers: { type: 'object' },
    json: {
      type: 'object',
      properties: {
        ok: { type: 'boolean', description: 'Whether it worked' },
        messages: {
          type: 'array',
          description: 'The messages',
          items: {
            type: 'object',
            properties: {
              ts: { type: 'string', description: 'Timestamp' },
              user: { type: ['string', 'null'] },
              reactions: { type: 'array', items: { type: 'object', properties: { name: { type: 'string' } } } },
            },
          },
        },
      },
    },
  },
};

describe('outputFields', () => {
  const { fields } = outputFields(REST);
  const byPath = (path: string) => fields.find((f) => f.path === path);

  it('lists the envelope and what is below it, depth first and in schema order', () => {
    expect(fields.map((f) => f.path)).toEqual([
      'status', 'ok', 'headers', 'json', 'json.ok', 'json.messages',
      'json.messages[0].ts', 'json.messages[0].user', 'json.messages[0].reactions',
      'json.messages[0].reactions[0].name',
    ]);
  });

  it('names the type, a list by its element type, and a nullable type by the one it has', () => {
    expect(byPath('status')?.type).toBe('integer');
    expect(byPath('json')?.type).toBe('object');
    expect(byPath('json.messages')?.type).toBe('list of object');
    expect(byPath('json.messages[0].user')?.type).toBe('string');
  });

  it('carries the description', () => {
    expect(byPath('json.messages[0].ts')?.description).toBe('Timestamp');
    expect(byPath('status')?.description).toBe('');
  });

  it('says enum for a pick list and any for a field without a type', () => {
    const out = outputFields({ properties: { kind: { enum: ['a', 'b'] }, blob: {} } });
    expect(out.fields.map((f) => `${f.path}:${f.type}`)).toEqual(['kind:enum', 'blob:any']);
  });

  it('is empty without a schema or without properties', () => {
    expect(outputFields(undefined)).toEqual({ fields: [], truncated: false });
    expect(outputFields({}).fields).toEqual([]);
    expect(outputFields({ properties: [] }).fields).toEqual([]);
  });

  it('stops at the limit and says so', () => {
    const properties = Object.fromEntries(Array.from({ length: 10 }, (_, i) => [`f${i}`, { type: 'string' }]));
    const out = outputFields({ properties }, 4);
    expect(out.fields).toHaveLength(4);
    expect(out.truncated).toBe(true);
  });

  it('does not follow a schema that nests without end', () => {
    const deep: Record<string, unknown> = { type: 'object', properties: {} };
    let node = deep;
    for (let i = 0; i < 20; i++) {
      const next: Record<string, unknown> = { type: 'object', properties: {} };
      (node.properties as Record<string, unknown>).next = next;
      node = next;
    }
    expect(outputFields(deep).fields.length).toBeLessThanOrEqual(6);
  });
});

describe('placeholderFor', () => {
  it('reads a path of what a state kept', () => {
    expect(placeholderFor('out', 'json.messages[0].ts')).toBe('${state.out.json.messages[0].ts}');
  });
});

describe('filterFields', () => {
  const { fields } = outputFields({
    properties: {
      status: { type: 'integer' },
      messages: { type: 'array', description: 'The messages', items: { type: 'object', properties: { ts: { type: 'string', description: 'Timestamp' } } } },
    },
  });

  it('keeps everything for an empty query', () => {
    expect(filterFields(fields, '  ')).toHaveLength(fields.length);
  });

  it('matches the path and the description, ignoring case', () => {
    expect(filterFields(fields, 'MESSAGES').map((f) => f.path)).toEqual(['messages', 'messages[0].ts']);
    expect(filterFields(fields, 'timestamp').map((f) => f.path)).toEqual(['messages[0].ts']);
    expect(filterFields(fields, 'nothing here')).toEqual([]);
  });
});
