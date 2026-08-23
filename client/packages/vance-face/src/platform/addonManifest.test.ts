import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  addonRemoteEntry,
  addonRemoteName,
  loadAddonManifest,
} from './addonManifest';

const originalFetch = globalThis.fetch;

function respond(init: { ok: boolean; body?: unknown }): void {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: init.ok,
    json: async () => init.body,
  }) as unknown as typeof fetch;
}

describe('loadAddonManifest', () => {
  beforeEach(() => {
    globalThis.fetch = originalFetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('returns the installed addons', async () => {
    respond({ ok: true, body: [{ name: 'store', path: '/addons/store' }] });

    await expect(loadAddonManifest()).resolves.toEqual([
      { name: 'store', path: '/addons/store' },
    ]);
    expect(globalThis.fetch).toHaveBeenCalledWith('/face/addons', {
      headers: { Accept: 'application/json' },
    });
  });

  it('reads a missing manifest as "no addons"', async () => {
    respond({ ok: false });

    await expect(loadAddonManifest()).resolves.toEqual([]);
  });

  it('reads an unreachable host as "no addons"', async () => {
    globalThis.fetch = vi.fn().mockRejectedValue(new Error('offline')) as unknown as typeof fetch;

    await expect(loadAddonManifest()).resolves.toEqual([]);
  });

  it('reads a non-array body as "no addons" instead of handing it on', async () => {
    // The file is written at face boot; a half-written or error-page body must
    // not reach callers that only ever `.filter()` it.
    respond({ ok: true, body: { error: 'nope' } });

    await expect(loadAddonManifest()).resolves.toEqual([]);
  });
});

describe('addon remote naming', () => {
  it('matches the dev-server middleware and nginx layout', () => {
    expect(addonRemoteName('simpleauth')).toBe('vance_addon_simpleauth');
    expect(addonRemoteEntry('simpleauth')).toBe('/addons/simpleauth/remoteEntry.js');
  });
});
