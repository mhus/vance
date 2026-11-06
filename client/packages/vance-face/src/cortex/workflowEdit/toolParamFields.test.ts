import { describe, expect, it } from 'vitest';
import type { ToolServiceDto } from '@vance/generated';
import { findTool, fromParamDraft, INVALID, paramFields, toParamDraft } from './toolParamFields';

const SCHEMA = {
  type: 'object',
  required: ['channel'],
  properties: {
    limit: { type: 'integer', description: 'How many', default: 100 },
    channel: { type: 'string', description: 'Channel id' },
    mode: { enum: ['a', 'b'] },
    flag: { type: ['boolean', 'null'] },
    ratio: { type: 'number' },
    blocks: { type: 'array', items: { type: 'object' } },
    either: { anyOf: [{ type: 'string' }, { type: 'integer' }] },
  },
};

const field = (name: string) => paramFields(SCHEMA).find((f) => f.name === name)!;

describe('paramFields', () => {
  it('puts required fields first and keeps the schema order within each group', () => {
    expect(paramFields(SCHEMA).map((f) => f.name)).toEqual(
      ['channel', 'limit', 'mode', 'flag', 'ratio', 'blocks', 'either'],
    );
  });

  it('derives the kind: scalar, enum, nullable scalar, and YAML for anything nested', () => {
    expect(field('limit').kind).toBe('integer');
    expect(field('channel').kind).toBe('string');
    expect(field('mode').kind).toBe('enum');
    expect(field('mode').options).toEqual(['a', 'b']);
    expect(field('flag').kind).toBe('boolean');
    expect(field('ratio').kind).toBe('number');
    expect(field('blocks').kind).toBe('yaml');
    expect(field('either').kind).toBe('yaml');
  });

  it('carries required, description and default', () => {
    expect(field('channel').required).toBe(true);
    expect(field('limit').description).toBe('How many');
    expect(field('limit').defaultText).toBe('100');
    expect(field('limit').required).toBe(false);
  });

  it('is empty for a schema without properties', () => {
    expect(paramFields(undefined)).toEqual([]);
    expect(paramFields({})).toEqual([]);
    expect(paramFields({ properties: [] })).toEqual([]);
  });
});

describe('fromParamDraft', () => {
  it('reads numbers and booleans, refuses text that is none', () => {
    expect(fromParamDraft(field('limit'), ' 50 ')).toBe(50);
    expect(fromParamDraft(field('limit'), '1.5')).toBe(INVALID);
    expect(fromParamDraft(field('ratio'), '0.25')).toBe(0.25);
    expect(fromParamDraft(field('ratio'), 'x')).toBe(INVALID);
    expect(fromParamDraft(field('flag'), 'true')).toBe(true);
    expect(fromParamDraft(field('flag'), 'false')).toBe(false);
    expect(fromParamDraft(field('flag'), 'yes')).toBe(INVALID);
  });

  it('lets a placeholder through whatever the type', () => {
    expect(fromParamDraft(field('limit'), '${params.n}')).toBe('${params.n}');
    expect(fromParamDraft(field('flag'), ' ${state.ok} ')).toBe('${state.ok}');
    expect(fromParamDraft(field('blocks'), '${state.blocks}')).toBe('${state.blocks}');
  });

  it('keeps a string as typed and removes the parameter for an empty one', () => {
    expect(fromParamDraft(field('channel'), ' C1 ')).toBe(' C1 ');
    expect(fromParamDraft(field('channel'), '  ')).toBeUndefined();
    expect(fromParamDraft(field('mode'), ' a ')).toBe('a');
  });

  it('parses YAML for a nested field and refuses broken YAML', () => {
    expect(fromParamDraft(field('blocks'), '- type: section\n- type: divider')).toEqual([
      { type: 'section' }, { type: 'divider' },
    ]);
    expect(fromParamDraft(field('blocks'), '[')).toBe(INVALID);
  });
});

describe('toParamDraft', () => {
  it('shows scalars as text and nested values as YAML', () => {
    expect(toParamDraft(field('limit'), 50)).toBe('50');
    expect(toParamDraft(field('flag'), false)).toBe('false');
    expect(toParamDraft(field('blocks'), [{ type: 'section' }])).toBe('- type: section');
    expect(toParamDraft(field('channel'), undefined)).toBe('');
  });
});

describe('findTool', () => {
  it('finds a tool in any service', () => {
    const tool = { name: 'a__x' } as ToolServiceDto['tools'][number];
    const services = [{ service: 'a', tools: [tool] }, { service: 'b', tools: [] }] as ToolServiceDto[];
    expect(findTool(services, 'a__x')).toBe(tool);
    expect(findTool(services, 'nope')).toBeUndefined();
  });
});
