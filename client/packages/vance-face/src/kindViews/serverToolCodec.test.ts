import { describe, expect, it } from 'vitest';
import {
  ServerToolParseError,
  applyFields,
  fieldsFromDoc,
  parseServerToolDoc,
  serializeServerToolDoc,
  serverToolDocFor,
  toolNameFromPath,
  toolPath,
} from './serverToolCodec';

describe('parseServerToolDoc / serializeServerToolDoc', () => {
  it('keeps keys the form does not own across a form round-trip', () => {
    const body = [
      'name: jira_rest',
      'type: rest_api',
      'description: Jira REST.',
      'parameters:',
      '  baseUrl: https://jira.example.com',
      'enabled: true',
      'primary: false',
      'defaultDeferred: true',
      'futureKey: kept',
    ].join('\n');

    const doc = parseServerToolDoc(body);
    const fields = fieldsFromDoc(doc);
    fields.description = 'Jira REST, edited.';
    const out = parseServerToolDoc(serializeServerToolDoc(applyFields(doc, fields)));

    expect(out).toEqual({
      name: 'jira_rest',
      type: 'rest_api',
      description: 'Jira REST, edited.',
      parameters: { baseUrl: 'https://jira.example.com' },
      enabled: true,
      primary: false,
      defaultDeferred: true,
      futureKey: 'kept',
    });
  });

  it('reads an empty body as an empty map', () => {
    expect(parseServerToolDoc('  \n')).toEqual({});
  });

  it('rejects a body that is not a mapping', () => {
    expect(() => parseServerToolDoc('- a\n- b')).toThrow(ServerToolParseError);
    expect(() => parseServerToolDoc('type: [unclosed')).toThrow(ServerToolParseError);
  });
});

describe('fieldsFromDoc', () => {
  it('applies the loader defaults to unset keys', () => {
    expect(fieldsFromDoc({ type: 'mcp_server', description: 'x' })).toEqual({
      type: 'mcp_server',
      description: 'x',
      parameters: {},
      labels: [],
      enabled: true,
      primary: false,
      disabledSubTools: [],
      defaultDeferred: false,
      promptHint: '',
    });
  });
});

describe('applyFields', () => {
  it('drops empty optional keys, like the server writer', () => {
    const doc = { type: 'mcp_server', description: 'x', labels: ['a'], disabledSubTools: ['b'], promptHint: 'p' };
    const fields = { ...fieldsFromDoc(doc), labels: [], disabledSubTools: [], promptHint: ' ' };
    expect(applyFields(doc, fields)).toEqual({
      type: 'mcp_server',
      description: 'x',
      enabled: true,
      primary: false,
      defaultDeferred: false,
    });
  });

  it('writes the owned keys in the server writer order, name first', () => {
    const doc = serverToolDocFor('my_tool', {
      ...fieldsFromDoc({}),
      type: 'mcp_server',
      description: 'd',
      parameters: { url: 'https://x/mcp' },
    });
    expect(Object.keys(doc)).toEqual(['name', 'type', 'description', 'parameters', 'enabled', 'primary', 'defaultDeferred']);
  });
});

describe('paths', () => {
  it('maps tool names to paths and back', () => {
    expect(toolPath('My_Tool')).toBe('_vance/server-tools/my_tool.yaml');
    expect(toolNameFromPath('_vance/server-tools/my_tool.yaml')).toBe('my_tool');
    expect(toolNameFromPath('_vance/server-tools/sub/x.yaml')).toBeNull();
    expect(toolNameFromPath('_vance/scheduler/x.yaml')).toBeNull();
    expect(toolNameFromPath(undefined)).toBeNull();
  });
});
