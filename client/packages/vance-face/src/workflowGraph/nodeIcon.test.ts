import { describe, expect, it } from 'vitest';
import type { ToolServiceDto } from '@vance/generated';
import { resolveNodeIcon, toolNodeIcon } from './nodeIcon';

const url = (path: string): string => `/assets/${path}`;

function service(name: string, tools: string[], icon?: string): ToolServiceDto {
  return {
    service: name,
    tools: tools.map((t) => ({ name: t })),
    catalog: icon === undefined ? undefined : { name, icon },
  } as unknown as ToolServiceDto;
}

describe('resolveNodeIcon', () => {
  it('knows a name of the UI vocabulary', () => {
    expect(resolveNodeIcon('terminal', url)).toEqual({ kind: 'ui', name: 'terminal' });
  });

  it('turns an asset path into an image address', () => {
    expect(resolveNodeIcon('icons/jira.svg', url)).toEqual({ kind: 'image', src: '/assets/icons/jira.svg' });
  });

  it('has none for nothing, for an unknown name, or for a path that is no asset address', () => {
    expect(resolveNodeIcon(undefined, url)).toBeNull();
    expect(resolveNodeIcon('', url)).toBeNull();
    expect(resolveNodeIcon('no-such-icon', url)).toBeNull();
    expect(resolveNodeIcon('../x.svg', url)).toBeNull();
    expect(resolveNodeIcon('icons/x.css', url)).toBeNull();
    expect(resolveNodeIcon('Icons/X.svg', url)).toBeNull();
  });

  it('has none when there is no session to build an address for', () => {
    expect(resolveNodeIcon('icons/jira.svg', () => '')).toBeNull();
  });
});

describe('toolNodeIcon', () => {
  const services = [
    service('jira_rest', ['jira_rest__getIssue'], 'icons/jira.svg'),
    service('slack_rest', ['slack_rest__chat_postMessage'], 'chat'),
    service('plain', ['plain__x']),
  ];

  it('takes the icon of the service that lists the tool', () => {
    expect(toolNodeIcon('jira_rest__getIssue', services, url)).toEqual({
      kind: 'image',
      src: '/assets/icons/jira.svg',
    });
    expect(toolNodeIcon('slack_rest__chat_postMessage', services, url)).toEqual({ kind: 'ui', name: 'chat' });
  });

  it('has none for a service without an icon, an unknown tool, or no tool', () => {
    expect(toolNodeIcon('plain__x', services, url)).toBeNull();
    expect(toolNodeIcon('nobody__x', services, url)).toBeNull();
    expect(toolNodeIcon(undefined, services, url)).toBeNull();
  });
});
