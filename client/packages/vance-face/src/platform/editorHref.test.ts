import { describe, expect, it } from 'vitest';
import { editorHref } from './editorHref';

/**
 * The one place that builds surface URLs — these tests pin the URL shape
 * for both clusters and standalone entries, including the query contract
 * (empty values are omitted, not sent blank).
 */

describe('editorHref', () => {
  it('builds cluster paths without .html and keeps the query verbatim', () => {
    expect(editorHref('home')).toBe('/');
    expect(editorHref('cortex', { project: 'p', doc: 'd' })).toBe('/cortex?project=p&doc=d');
  });

  it('builds standalone entries as /<surface>.html', () => {
    expect(editorHref('profile')).toBe('/profile.html');
    expect(editorHref('users')).toBe('/users.html');
    expect(editorHref('settings')).toBe('/settings.html');
  });

  it('omits null, undefined and empty params instead of sending them blank', () => {
    expect(editorHref('settings', { scope: 'user', form: null, tab: undefined, doc: '' }))
      .toBe('/settings.html?scope=user');
  });

  it('encodes unsafe characters in query values', () => {
    expect(editorHref('settings', { scope: 'team space' }))
      .toBe('/settings.html?scope=team+space');
  });
});
