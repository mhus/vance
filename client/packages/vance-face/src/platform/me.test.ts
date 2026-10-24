import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MeDto } from '@vance/generated';

// The module keeps one answer per page — load it fresh for every test.
const getSessionData = vi.fn();
const brainFetch = vi.fn();
const store = new Map<string, string>();
vi.mock('./webUiSession', () => ({ getSessionData: () => getSessionData() }));
vi.mock('@vance/shared', () => ({
  brainFetch: (...args: unknown[]) => brainFetch(...args),
  StorageKeys: { meSnapshot: 'vance.me.snapshot' },
  getStorage: () => ({
    prefsStore: {
      get: (k: string) => store.get(k) ?? null,
      set: (k: string, v: string) => store.set(k, v),
      remove: (k: string) => store.delete(k),
    },
  }),
}));

function me(username: string, theme: string): MeDto {
  return { username, tenantId: 'acme', tenantRole: 'USER', settings: { 'webui.theme': theme } } as unknown as MeDto;
}

async function fresh(): Promise<typeof import('./me')> {
  vi.resetModules();
  return import('./me');
}

describe('me', () => {
  beforeEach(() => {
    store.clear();
    brainFetch.mockReset();
    getSessionData.mockReturnValue({ tenantId: 'acme', username: 'alice' });
  });

  it('serves the snapshot of the last answer before the fresh one is in', async () => {
    brainFetch.mockResolvedValue(me('alice', 'dark'));
    await (await fresh()).loadMe();

    const next = await fresh();
    expect(next.meSetting('webui.theme')).toBe('dark');
  });

  it('ignores a snapshot that belongs to another account', async () => {
    brainFetch.mockResolvedValue(me('alice', 'dark'));
    await (await fresh()).loadMe();

    getSessionData.mockReturnValue({ tenantId: 'acme', username: 'bob' });
    expect((await fresh()).getMe()).toBeNull();
  });

  it('asks once when boot and a view load at the same moment', async () => {
    brainFetch.mockResolvedValue(me('alice', 'light'));
    const mod = await fresh();

    await Promise.all([mod.loadMe(), mod.loadMe()]);

    expect(brainFetch).toHaveBeenCalledTimes(1);
  });

  it('tells listeners what changed', async () => {
    const mod = await fresh();
    const seen: Array<[string | undefined, string | undefined]> = [];
    mod.onMeChanged((next, prev) => seen.push([prev?.settings?.['webui.theme'], next?.settings?.['webui.theme']]));
    brainFetch.mockResolvedValueOnce(me('alice', 'light')).mockResolvedValueOnce(me('alice', 'dark'));

    await mod.loadMe();
    await mod.loadMe();

    expect(seen).toEqual([[undefined, 'light'], ['light', 'dark']]);
  });

  it('returns a project-scoped answer without replacing the shared one', async () => {
    const mod = await fresh();
    const seen: string[] = [];
    mod.onMeChanged(() => seen.push('changed'));
    brainFetch.mockResolvedValueOnce(me('alice', 'light')).mockResolvedValueOnce(me('alice', 'dark'));

    await mod.loadMe();
    const scoped = await mod.loadMe('rocket');

    expect(brainFetch).toHaveBeenLastCalledWith('GET', 'me?project=rocket');
    expect(scoped?.settings?.['webui.theme']).toBe('dark');
    expect(mod.meSetting('webui.theme')).toBe('light');
    expect(seen).toEqual(['changed']);
    expect(JSON.parse(store.get('vance.me.snapshot')!).me.settings['webui.theme']).toBe('light');
  });

  it('keeps the previous answer when the call fails', async () => {
    const mod = await fresh();
    brainFetch.mockResolvedValueOnce(me('alice', 'dark')).mockRejectedValueOnce(new Error('offline'));

    await mod.loadMe();
    expect(await mod.loadMe()).toBeNull();

    expect(mod.meSetting('webui.theme')).toBe('dark');
  });

  it('waits for the first answer when nothing is known yet', async () => {
    const mod = await fresh();
    brainFetch.mockResolvedValue(me('alice', 'dark'));

    expect(mod.getMe()).toBeNull();
    await mod.ensureMe();

    expect(mod.meSetting('webui.theme')).toBe('dark');
    await mod.ensureMe();
    expect(brainFetch).toHaveBeenCalledTimes(1);
  });

  it('does not ask without a session', async () => {
    getSessionData.mockReturnValue(null);
    const mod = await fresh();

    expect(await mod.ensureMe()).toBeNull();
    expect(brainFetch).not.toHaveBeenCalled();
  });

  it('forgets everything on logout', async () => {
    const mod = await fresh();
    brainFetch.mockResolvedValue(me('alice', 'dark'));
    await mod.loadMe();

    mod.clearMe();

    expect(mod.getMe()).toBeNull();
    expect(store.size).toBe(0);
  });
});
