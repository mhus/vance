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

const event = (name: string, workflow: string, from?: string): EventSummary =>
  ({ name, workflow, from, enabled: true, authConfigured: true, source: 'PROJECT' }) as unknown as EventSummary;
const scheduler = (name: string, workflow: string | undefined, source = 'PROJECT'): SchedulerSummary =>
  ({ name, workflow, cron: '0 0 6 * * *', enabled: true, source }) as unknown as SchedulerSummary;

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
    [event('b', 'audit'), event('a', 'audit', 'github/push'), event('c', 'other')],
    [scheduler('nightly', 'audit'), scheduler('chat', undefined), scheduler('shared', 'audit', 'TENANT')],
    'audit',
  );

  it('keeps only what starts the workflow, events first, each group by name', () => {
    expect(sources.map((s) => `${s.kind}:${s.name}`)).toEqual([
      'event:a', 'event:b', 'scheduler:nightly', 'scheduler:shared',
    ]);
  });

  it('lets the editor remove a catalog event and a scheduler of the project, nothing else', () => {
    expect(sources.map((s) => s.removable)).toEqual([true, false, true, false]);
    expect(sources[0].from).toBe('github/push');
    expect(sources[2].summary).toBe('0 0 6 * * *');
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
