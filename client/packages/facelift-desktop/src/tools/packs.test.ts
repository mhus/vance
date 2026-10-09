/**
 * Behavioural tests for the tool-pack config logic — merge, selection,
 * trust matching and spec mapping (planning/desktop-tool-packs.md
 * §3–§6). Connection/lifecycle concerns live in packService; these pin
 * the pure semantics so the mcp.json contract cannot drift.
 */
import { describe, expect, it } from 'vitest';

import {
  applySelection,
  buildPackToolEntry,
  filterSubTools,
  isTrusted,
  mergeServers,
  packToolName,
  parseMcpJson,
  parseToolPacksSelection,
  parseTrustedPacks,
  reachOf,
  serializeTrustedPacks,
} from './packs';

const GLOBAL = JSON.stringify({
  mcpServers: {
    chrome: { command: ['npx', '-y', 'chrome-devtools-mcp@latest'], labels: ['browser'] },
    jira: { command: ['jira-mcp'] },
  },
});

const PROJECT = JSON.stringify({
  mcpServers: {
    chrome: { command: ['node', 'local-chrome.mjs'], labels: ['browser', 'local'] },
    extra: { command: ['local-helper'] },
    silenced: { command: ['x'], enabled: false },
  },
});

describe('parseMcpJson', () => {
  it('parses a well-formed mcpServers map', () => {
    const file = parseMcpJson(GLOBAL);
    expect(Object.keys(file.mcpServers ?? {}).sort()).toEqual(['chrome', 'jira']);
  });

  it('corrupt or missing content yields an empty toolbox', () => {
    expect(parseMcpJson('{ not json')).toEqual({});
    expect(parseMcpJson('')).toEqual({});
  });
});

describe('mergeServers', () => {
  it('project wins by name, contributes new servers, marks origins', () => {
    const merged = mergeServers(parseMcpJson(GLOBAL), parseMcpJson(PROJECT));
    const byName = new Map(merged.map((s) => [s.name, s]));
    expect(byName.get('chrome')?.origin).toBe('project');
    expect(byName.get('chrome')?.config.command).toEqual(['node', 'local-chrome.mjs']);
    expect(byName.get('jira')?.origin).toBe('global');
    expect(byName.get('extra')?.origin).toBe('project');
    expect(merged).toHaveLength(4);
  });
});

describe('applySelection', () => {
  const servers = mergeServers(parseMcpJson(GLOBAL), parseMcpJson(PROJECT));

  it('without steering: only the files own enabled flag applies', () => {
    const names = applySelection(servers, null).map((s) => s.name);
    expect(names).not.toContain('silenced');
    expect(names).toContain('chrome');
    expect(names).toContain('jira');
  });

  it('toolPacks.enabled=false disables the whole toolbox', () => {
    expect(applySelection(servers, { enabled: false })).toEqual([]);
  });

  it('packs allow-list then disabledPacks deny-list, exact names', () => {
    const a = applySelection(servers, { packs: ['chrome', 'extra'] });
    expect(a.map((s) => s.name)).toEqual(['chrome', 'extra']);
    const b = applySelection(servers, { disabledPacks: ['jira'] });
    expect(b.map((s) => s.name)).not.toContain('jira');
    // prefix matching would be a footgun — 'chrom' must not select 'chrome'
    const c = applySelection(servers, { packs: ['chrom'] });
    expect(c).toEqual([]);
  });

  it('a corrupt config.yaml is no steering, not empty steering', () => {
    expect(parseToolPacksSelection(':broken: [')).toBeNull();
    expect(parseToolPacksSelection(null)).toBeNull();
    const steered = applySelection(servers, parseToolPacksSelection('toolPacks:\n  enabled: true\n'));
    expect(steered.map((s) => s.name)).toContain('jira');
  });
});

describe('trust', () => {
  it('matches on workdir + name + reach; a swapped command re-asks', () => {
    const trusted = parseTrustedPacks(
      serializeTrustedPacks({
        '/repo': [{ name: 'chrome', reach: 'node local-chrome.mjs' }],
      }),
    );
    expect(isTrusted(trusted, '/repo', 'chrome', 'node local-chrome.mjs')).toBe(true);
    expect(isTrusted(trusted, '/repo', 'chrome', 'other-command')).toBe(false);
    expect(isTrusted(trusted, '/repo', 'extra', 'node local-chrome.mjs')).toBe(false);
    expect(isTrusted(trusted, '/other', 'chrome', 'node local-chrome.mjs')).toBe(false);
  });

  it('corrupt trust file is nothing trusted (fail-closed)', () => {
    expect(parseTrustedPacks('::: not yaml [')).toEqual({});
  });

  it('reach is the joined command', () => {
    expect(reachOf({ command: ['npx', '-y', 'x'] })).toBe('npx -y x');
    expect(reachOf({})).toBe('');
  });
});

describe('spec mapping', () => {
  it('wire name, labels, deferred, desktop profile, conservative safety', () => {
    const entry = buildPackToolEntry('chrome', ['browser'], {
      name: 'take_snapshot',
      description: 'Take a snapshot.',
      inputSchema: { type: 'object', properties: { url: { type: 'string' } } },
    });
    expect(entry.spec.name).toBe('chrome__take_snapshot');
    expect(entry.pack).toBe('chrome');
    expect(entry.tool).toBe('take_snapshot');
    expect(entry.spec.labels).toEqual(['browser', 'mcp', 'mcp:chrome', 'side-effect']);
    expect(entry.spec.allowedProfiles).toEqual(['desktop']);
    expect(entry.spec.deferred).toBe(true);
    expect(entry.spec.safety).toBe('MUTATING');
    expect(entry.spec.source).toBe('desktop');
  });

  it('missing description/schema get safe defaults', () => {
    const entry = buildPackToolEntry('bare', [], { name: 'noop' });
    expect(entry.spec.description).toContain('noop');
    expect(entry.spec.paramsSchema).toEqual({ type: 'object', properties: {} });
  });

  it('packToolName is the family anchor (budget: prefix before __)', () => {
    expect(packToolName('chrome', 'navigate')).toBe('chrome__navigate');
  });
});

describe('filterSubTools', () => {
  it('drops disabled sub tools of a pack', () => {
    const tools = [{ name: 'chrome__a' }, { name: 'chrome__b' }];
    const kept = filterSubTools(tools, 'chrome', ['b']);
    expect(kept).toEqual([{ name: 'chrome__a' }]);
  });

  it('no disabledSubTools = all kept', () => {
    const tools = [{ name: 'chrome__a' }];
    expect(filterSubTools(tools, 'chrome', undefined)).toEqual(tools);
    expect(filterSubTools(tools, 'chrome', [])).toEqual(tools);
  });
});
