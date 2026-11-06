import { describe, expect, it } from 'vitest';
import type { ToolServiceDto } from '@vance/generated';
import { paletteGroups, toolLabel } from './paletteModel';

function tool(name: string, description = ''): ToolServiceDto['tools'][number] {
  return {
    name, description, primary: false, paramsSchema: {}, labels: [], allowedProfiles: [],
    deferred: false, searchHint: '', safety: 'SAFE' as never, requiresEngineRoles: [],
  };
}

const SLACK: ToolServiceDto = {
  service: 'slack_rest',
  tools: [
    tool('slack_rest__chat_post_message', 'Post a message'),
    tool('slack_rest__conversations_history', 'Read a channel'),
    tool('slack_rest__users_list', 'List users'),
  ],
  catalog: {
    title: 'Slack', description: 'Team chat', loginProvider: 'slack', connected: false,
    highlights: [
      { tool: 'slack_rest__chat_post_message', description: 'Send a message', params: { channel: 'C1' } },
      { tool: 'slack_rest__gone', description: 'not offered by the pack' },
    ],
    triggers: [],
  },
};
const BUILTIN: ToolServiceDto = {
  service: 'builtin',
  tools: Array.from({ length: 10 }, (_, i) => tool(`doc_tool_${i}`, i === 3 ? 'reads a document' : '')),
};

describe('toolLabel', () => {
  it('drops the pack prefix', () => {
    expect(toolLabel('slack_rest__chat_post_message')).toBe('chat_post_message');
    expect(toolLabel('doc_read')).toBe('doc_read');
  });
});

describe('paletteGroups without a query', () => {
  const groups = paletteGroups([BUILTIN, SLACK], '');

  it('shows a catalog entry first, with its highlights that the pack really offers', () => {
    expect(groups.map((g) => g.service)).toEqual(['slack_rest', 'builtin']);
    const slack = groups[0];
    expect(slack.title).toBe('Slack');
    expect(slack.tools.map((t) => t.name)).toEqual(['slack_rest__chat_post_message']);
    expect(slack.tools[0].description).toBe('Send a message');
    expect(slack.tools[0].params).toEqual({ channel: 'C1' });
    expect(slack.total).toBe(3);
  });

  it('cuts a service without highlights to its first few tools', () => {
    expect(groups[1].tools).toHaveLength(6);
    expect(groups[1].total).toBe(10);
  });

  it('says when the login is missing', () => {
    expect(groups[0].needsLogin).toBe(true);
    expect(groups[1].needsLogin).toBe(false);
  });
});

describe('paletteGroups with a query', () => {
  it('matches name and description case-insensitively and drops empty services', () => {
    const groups = paletteGroups([BUILTIN, SLACK], 'HISTORY');
    expect(groups.map((g) => g.service)).toEqual(['slack_rest']);
    expect(groups[0].tools.map((t) => t.label)).toEqual(['conversations_history']);
    expect(paletteGroups([BUILTIN, SLACK], 'reads a doc')[0].tools.map((t) => t.name)).toEqual(['doc_tool_3']);
  });

  it('takes every tool of a service whose name matches', () => {
    expect(paletteGroups([SLACK], 'slack')[0].tools).toHaveLength(3);
  });

  it('finds nothing for a word nobody has', () => {
    expect(paletteGroups([BUILTIN, SLACK], 'zzz')).toEqual([]);
  });
});
