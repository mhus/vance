import { describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({
  BrowserWindow: {},
  app: {},
  ipcMain: { handle: vi.fn() },
  shell: {},
}));

import { prepareStartUrl } from './browserSignIn';

describe('prepareStartUrl', () => {
  const page = 'https://vance.example.com/login.html?tenant=acme';

  it('adds the loopback redirect to the own start endpoint', () => {
    const url = prepareStartUrl(
      'https://vance.example.com/brain/_sso/start?tenant=acme&app_challenge=abc',
      page,
      53123,
    );
    expect(url).not.toBeNull();
    const parsed = new URL(url as string);
    expect(parsed.searchParams.get('app_redirect')).toBe('http://127.0.0.1:53123/sso-done');
    expect(parsed.searchParams.get('app_challenge')).toBe('abc');
  });

  it('replaces a redirect the page tried to set itself', () => {
    const url = prepareStartUrl(
      'https://vance.example.com/brain/_sso/start?app_redirect=https%3A%2F%2Fevil.example',
      page,
      1,
    );
    expect(new URL(url as string).searchParams.getAll('app_redirect')).toEqual(['http://127.0.0.1:1/sso-done']);
  });

  it('refuses other origins and other paths', () => {
    expect(prepareStartUrl('https://evil.example/brain/_sso/start', page, 1)).toBeNull();
    expect(prepareStartUrl('https://vance.example.com/anything', page, 1)).toBeNull();
    expect(prepareStartUrl('not a url', page, 1)).toBeNull();
  });
});
