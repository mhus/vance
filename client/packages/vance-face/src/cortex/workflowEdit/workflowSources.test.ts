import { describe, expect, it } from 'vitest';
import { parse } from 'yaml';
import type { EventSummary, SchedulerSummary, ToolServiceDto } from '@vance/generated';
import {
  catalogTriggers,
  freeSourceName,
  schedulerYaml,
  sourcesOf,
  workflowNameOf,
} from './workflowSources';

const event = (name: string, workflow: string, from?: string, source = 'PROJECT'): EventSummary =>
  ({ name, workflow, from, enabled: true, authConfigured: true, source }) as unknown as EventSummary;
const scheduler = (
  name: string,
  workflow: string | undefined,
  source = 'PROJECT',
  tags?: string[],
): SchedulerSummary =>
  ({ name, workflow, cron: '0 0 6 * * *', enabled: true, source, tags }) as unknown as SchedulerSummary;

describe('workflowNameOf', () => {
  it('is the name under _vance/workflows/, also with a folder, and null elsewhere', () => {
    expect(workflowNameOf('_vance/workflows/audit.yaml')).toBe('audit');
    expect(workflowNameOf('_vance/workflows/team/audit.yml')).toBe('team/audit');
    expect(workflowNameOf('drafts/audit.yaml')).toBeNull();
    expect(workflowNameOf(undefined)).toBeNull();
  });
});

describe('sourcesOf', () => {
  const sources = sourcesOf(
    [event('b', 'audit'), event('a', 'audit', 'github/push'), event('c', 'other'), event('t', 'audit', undefined, 'TENANT')],
    [
      scheduler('nightly', 'audit'),
      scheduler('chat', undefined),
      scheduler('shared', 'audit', 'TENANT'),
      scheduler('inbox', 'audit', 'PROJECT', ['catalog', 'slack/new-message']),
      scheduler('tagged', 'audit', 'PROJECT', ['ops']),
    ],
    'audit',
  );

  it('keeps only what starts the workflow, events first, each group by name', () => {
    expect(sources.map((s) => `${s.kind}:${s.name}`)).toEqual([
      'event:a', 'event:b', 'event:t', 'scheduler:inbox', 'scheduler:nightly', 'scheduler:shared',
      'scheduler:tagged',
    ]);
  });

  it('lets the editor remove what the project owns and nothing inherited from the tenant', () => {
    expect(sources.map((s) => s.removable)).toEqual([true, true, false, true, true, false, true]);
    expect(sources[0].from).toBe('github/push');
    expect(sources[3].summary).toBe('0 0 6 * * *');
  });

  it('names the origin of a scheduler made from a catalog trigger, and of no other', () => {
    expect(sources.filter((s) => s.kind === 'scheduler').map((s) => s.from)).toEqual([
      'slack/new-message', undefined, undefined, undefined,
    ]);
  });
});

describe('catalogTriggers', () => {
  const trigger = { name: 'push', description: '', mode: 'webhook', subscribes: true, inputs: [] };
  const catalog = { name: 'github', title: 'GitHub', description: '', highlights: [], triggers: [trigger] };

  it('lists the triggers of each entry once, under the entry name', () => {
    const services = [
      { service: 'github_rest', tools: [], catalog },
      { service: 'github', tools: [], catalog },
      { service: 'plain', tools: [] },
    ] as unknown as ToolServiceDto[];
    expect(catalogTriggers(services)).toEqual([{ service: 'github', serviceTitle: 'GitHub', trigger }]);
  });
});

describe('schedulerYaml', () => {
  it('writes description, cron and workflow', () => {
    expect(parse(schedulerYaml({ description: 'Daily', cron: '0 0 6 * * *', workflow: 'audit' }))).toEqual({
      description: 'Daily', cron: '0 0 6 * * *', workflow: 'audit',
    });
  });
});

describe('freeSourceName', () => {
  it('cleans the base and numbers it when taken', () => {
    expect(freeSourceName('slack new message', [])).toBe('slack-new-message');
    expect(freeSourceName('audit', ['audit', 'audit-2'])).toBe('audit-3');
    expect(freeSourceName('!!!', [])).toBe('source');
  });
});
