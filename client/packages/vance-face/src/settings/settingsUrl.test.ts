import { describe, expect, it } from 'vitest';
import {
  parseSettingsView,
  scopeKeyword,
  serializeSettingsView,
  settingsHref,
  type SettingsView,
} from './settingsUrl';

/**
 * The URL is the settings page's entire view state — these tests pin the
 * query contract: what round-trips, what is omitted as default, and how
 * the scope keyword vs. project name distinction reads back.
 */

const FULL_VIEW: SettingsView = {
  scope: 'tenant',
  tab: 'areas',
  form: 'llm-setup',
  area: 'vance-research-source',
  entry: '64a1f0c0e4b0a1a2b3c4d5e6',
  group: null,
};

const DEFAULTS_VIEW: SettingsView = {
  scope: 'user',
  tab: 'forms',
  form: null,
  area: null,
  entry: null,
  group: null,
};

const GROUP_VIEW: SettingsView = {
  ...DEFAULTS_VIEW,
  scope: 'tenant',
  tab: 'properties',
  group: 'main',
};

describe('parseSettingsView', () => {
  it('reads scope, tab, form, area and entry', () => {
    expect(parseSettingsView('?scope=tenant&tab=areas&form=llm-setup'
      + '&area=vance-research-source&entry=64a1f0c0e4b0a1a2b3c4d5e6', 'user'))
      .toEqual(FULL_VIEW);
  });

  it('falls back to the supplied default scope when the param is missing', () => {
    expect(parseSettingsView('', 'user')).toEqual({ ...DEFAULTS_VIEW, scope: 'user' });
  });

  it('treats a blank scope param as missing', () => {
    expect(parseSettingsView('?scope=%20%20', 'tenant').scope).toBe('tenant');
  });

  it('treats any non-keyword scope as a project name', () => {
    expect(parseSettingsView('?scope=research-2026', 'tenant').scope).toBe('research-2026');
  });

  it('maps unknown tab values to the forms default', () => {
    expect(parseSettingsView('?scope=user&tab=nonsense', 'tenant').tab).toBe('forms');
    expect(parseSettingsView('?scope=user&tab=raw', 'tenant').tab).toBe('raw');
    expect(parseSettingsView('?scope=user&tab=areas', 'tenant').tab).toBe('areas');
    expect(parseSettingsView('?scope=cluster-test&tab=properties', 'tenant').tab).toBe('properties');
    expect(parseSettingsView('?scope=user', 'tenant').tab).toBe('forms');
  });
});

describe('serializeSettingsView / settingsHref', () => {
  it('omits all defaults: bare scope', () => {
    expect(serializeSettingsView(DEFAULTS_VIEW)).toBe('?scope=user');
    expect(settingsHref(DEFAULTS_VIEW)).toBe('/settings.html?scope=user');
  });

  it('serializes non-default tabs and the open selections', () => {
    const view: SettingsView = { ...DEFAULTS_VIEW, scope: 'tenant', tab: 'raw' };
    expect(serializeSettingsView(view)).toBe('?scope=tenant&tab=raw');
    const withArea: SettingsView = {
      ...DEFAULTS_VIEW,
      scope: 'tenant',
      tab: 'areas',
      area: 'vance-research-source',
    };
    expect(serializeSettingsView(withArea))
      .toBe('?scope=tenant&tab=areas&area=vance-research-source');
  });

  it('serializes the selected group row and round-trips it', () => {
    expect(serializeSettingsView(GROUP_VIEW)).toBe('?scope=tenant&tab=properties&group=main');
    expect(parseSettingsView(serializeSettingsView(GROUP_VIEW), 'user')).toEqual(GROUP_VIEW);
  });
  it('round-trips through parse', () => {
    expect(parseSettingsView(serializeSettingsView(FULL_VIEW), 'user')).toEqual(FULL_VIEW);
  });
});

describe('scopeKeyword', () => {
  it('classifies the two keywords, everything else is a project', () => {
    expect(scopeKeyword('tenant')).toBe('tenant');
    expect(scopeKeyword('user')).toBe('user');
    expect(scopeKeyword('_tenant')).toBeNull();
    expect(scopeKeyword('research-2026')).toBeNull();
  });
});
