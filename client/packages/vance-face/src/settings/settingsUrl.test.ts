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

describe('parseSettingsView', () => {
  it('reads scope, form and the raw tab', () => {
    expect(parseSettingsView('?scope=tenant&form=llm-setup&tab=raw', 'user'))
      .toEqual({ scope: 'tenant', form: 'llm-setup', tab: 'raw' });
  });

  it('falls back to the supplied default scope when the param is missing', () => {
    expect(parseSettingsView('', 'user')).toEqual({ scope: 'user', form: null, tab: 'guided' });
  });

  it('treats a blank scope param as missing', () => {
    expect(parseSettingsView('?scope=%20%20', 'tenant').scope).toBe('tenant');
  });

  it('treats any non-keyword scope as a project name', () => {
    expect(parseSettingsView('?scope=research-2026', 'tenant').scope).toBe('research-2026');
  });

  it('ignores unknown tab values (guided is the default)', () => {
    expect(parseSettingsView('?scope=user&tab=nonsense', 'tenant').tab).toBe('guided');
  });
});

describe('serializeSettingsView / settingsHref', () => {
  it('omits defaults: no form, guided tab', () => {
    const view: SettingsView = { scope: 'user', form: null, tab: 'guided' };
    expect(serializeSettingsView(view)).toBe('?scope=user');
    expect(settingsHref(view)).toBe('/settings.html?scope=user');
  });

  it('round-trips through parse', () => {
    const view: SettingsView = { scope: 'research-2026', form: 'llm-setup', tab: 'raw' };
    expect(parseSettingsView(serializeSettingsView(view), 'tenant')).toEqual(view);
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
