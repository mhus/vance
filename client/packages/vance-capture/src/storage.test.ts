import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ConnectionBlob } from '@vance/shared/integration-connection';

/**
 * The stored-connection list, against a fake `chrome.storage`.
 *
 * <p>These are here because the failures are all silent ones. A migration that
 * drops the single stored connection presents as an extension that is suddenly
 * unconfigured — indistinguishable, from the outside, from a token that was
 * revoked. A renewal that appends instead of replacing leaves a picker with
 * three entries of which one works. Neither says anything at the time.
 *
 * <p>The module reads `chrome` at load, so each case stubs the global and then
 * imports a fresh copy.
 */

interface Fake {
  data: Record<string, unknown>;
  removed: string[][];
  granted: Set<string>;
}

let fake: Fake;

function install(initial: Record<string, unknown> = {}, granted: string[] = []): void {
  fake = { data: { ...initial }, removed: [], granted: new Set(granted) };
  const keysOf = (keys: string | string[]) => (Array.isArray(keys) ? keys : [keys]);
  vi.stubGlobal('chrome', {
    storage: {
      local: {
        get: async (keys: string | string[]) => {
          const out: Record<string, unknown> = {};
          for (const key of keysOf(keys)) {
            if (key in fake.data) out[key] = fake.data[key];
          }
          return out;
        },
        set: async (patch: Record<string, unknown>) => {
          Object.assign(fake.data, patch);
        },
        remove: async (keys: string | string[]) => {
          for (const key of keysOf(keys)) delete fake.data[key];
        },
      },
    },
    permissions: {
      contains: async ({ origins }: { origins: string[] }) =>
        origins.every((o) => fake.granted.has(o)),
      remove: async ({ origins }: { origins: string[] }) => {
        fake.removed.push(origins);
        for (const o of origins) fake.granted.delete(o);
        return true;
      },
    },
  });
}

async function load() {
  vi.resetModules();
  return import('./connection');
}

const blob = (over: Partial<ConnectionBlob> = {}): ConnectionBlob => ({
  brainUrl: 'https://eddie.example',
  tenant: 'acme',
  projectId: 'reading',
  target: 'links',
  profiles: ['links-capture'],
  token: 'tok-1',
  ...over,
});

beforeEach(() => {
  vi.unstubAllGlobals();
});

describe('connectionKey', () => {
  /**
   * The rule the whole list rests on: a renewal is the same destination, so
   * the credential cannot be part of the identity.
   */
  it('ignores the token — a renewed one is the same destination', async () => {
    install();
    const { connectionKey } = await load();
    expect(connectionKey(blob({ token: 'tok-2', expiresAt: 1 })))
      .toBe(connectionKey(blob()));
  });

  it('ignores decorative slashes and a trailing brain slash', async () => {
    install();
    const { connectionKey } = await load();
    expect(connectionKey(blob({ target: '/links/', brainUrl: 'https://eddie.example/' })))
      .toBe(connectionKey(blob()));
  });

  /** Two lists in one project are two destinations — the case a tenant key misses. */
  it('separates two folders in the same project', async () => {
    install();
    const { connectionKey } = await load();
    expect(connectionKey(blob({ target: 'work' })))
      .not.toBe(connectionKey(blob({ target: 'private' })));
  });

  /** A separator a value can also contain is how two destinations become one key. */
  it('does not collide when a folder contains the separator', async () => {
    install();
    const { connectionKey } = await load();
    expect(connectionKey(blob({ projectId: 'a', target: 'b/c' })))
      .not.toBe(connectionKey(blob({ projectId: 'a/b', target: 'c' })));
  });
});

describe('migration from the single-connection layout', () => {
  it('carries the connection and its grab folder into the list', async () => {
    install({ connection: blob(), grabFolder: 'web' });
    const { loadConnections, loadActive } = await load();

    const list = await loadConnections();
    expect(list).toHaveLength(1);
    expect(list[0].blob.token).toBe('tok-1');
    expect(list[0].grabFolder).toBe('web');

    // And it is the one the popup acts on — a migrated setup must not come up
    // pointing at nothing.
    const active = await loadActive();
    expect(active?.blob.token).toBe('tok-1');
  });

  it('drops the old keys, so it cannot run a second time over newer data', async () => {
    install({ connection: blob(), grabFolder: 'web' });
    const { loadConnections } = await load();
    await loadConnections();
    expect(fake.data.connection).toBeUndefined();
    expect(fake.data.grabFolder).toBeUndefined();
    expect(fake.data.connections).toHaveLength(1);
  });

  it('leaves an existing list alone even if a stale legacy key survived', async () => {
    install({
      connections: [{ blob: blob({ token: 'kept' }), label: 'Mine', grabFolder: 'clips' }],
      connection: blob({ token: 'stale' }),
    });
    const { loadConnections } = await load();
    const list = await loadConnections();
    expect(list).toHaveLength(1);
    expect(list[0].blob.token).toBe('kept');
  });

  it('reports nothing configured when there is nothing to migrate', async () => {
    install();
    const { loadConnections, loadActive } = await load();
    expect(await loadConnections()).toEqual([]);
    expect(await loadActive()).toBeNull();
  });

  /** Junk in storage should not take the settings page down with it. */
  it('skips records that are not connections', async () => {
    install({ connections: [{ label: 'no blob' }, 'nonsense', { blob: blob() }] });
    const { loadConnections } = await load();
    expect(await loadConnections()).toHaveLength(1);
  });
});

describe('upsertConnection', () => {
  it('replaces the record for the same destination and keeps what is local', async () => {
    install();
    const { upsertConnection, loadConnections } = await load();
    await upsertConnection(blob(), { label: 'Reading list' });
    await upsertConnection(blob({ token: 'tok-2' }), { label: 'Server says something else' });

    const list = await loadConnections();
    expect(list).toHaveLength(1);
    expect(list[0].blob.token).toBe('tok-2');
    // The name is the person's; a renewal is not an occasion to rename it.
    expect(list[0].label).toBe('Reading list');
  });

  it('keeps the grab folder across a renewal', async () => {
    install();
    const { upsertConnection, updateConnection, connectionKey, loadConnections } = await load();
    await upsertConnection(blob());
    await updateConnection(connectionKey(blob()), { grabFolder: 'clips' });
    await upsertConnection(blob({ token: 'tok-2' }));
    expect((await loadConnections())[0].grabFolder).toBe('clips');
  });

  it('adds a second destination for another folder in the same project', async () => {
    install();
    const { upsertConnection, loadConnections } = await load();
    await upsertConnection(blob({ target: 'work' }));
    await upsertConnection(blob({ target: 'private' }));
    expect(await loadConnections()).toHaveLength(2);
  });

  it('makes what was just pasted the active one', async () => {
    install();
    const { upsertConnection, loadActive } = await load();
    await upsertConnection(blob({ target: 'work' }));
    await upsertConnection(blob({ target: 'private' }));
    expect((await loadActive())?.blob.target).toBe('private');
  });
});

describe('removeConnection', () => {
  it('moves the active pointer off the record it just deleted', async () => {
    install();
    const { upsertConnection, removeConnection, connectionKey, loadActive } = await load();
    await upsertConnection(blob({ target: 'work' }));
    await upsertConnection(blob({ target: 'private' }));

    // 'private' was the active one.
    await removeConnection(connectionKey(blob({ target: 'private' })));
    expect((await loadActive())?.blob.target).toBe('work');
  });

  /**
   * A pointer at a record that is gone must not read as "unconfigured" — there
   * is a usable destination, and refusing to pick it makes a stale pointer look
   * like a lost setup.
   */
  it('falls back to the first when the stored pointer names nothing', async () => {
    install({
      connections: [{ blob: blob({ target: 'work' }), label: '', grabFolder: '' }],
      activeConnection: '["https://eddie.example","acme","reading","gone"]',
    });
    const { loadActive } = await load();
    expect((await loadActive())?.blob.target).toBe('work');
  });

  it('leaves nothing behind when the last one goes', async () => {
    install();
    const { upsertConnection, removeConnection, connectionKey, loadActive } = await load();
    await upsertConnection(blob());
    await removeConnection(connectionKey(blob()));
    expect(await loadActive()).toBeNull();
    expect(fake.data.activeConnection).toBeUndefined();
  });
});

describe('releaseHostAccess', () => {
  /** Tidying up one destination must not break another one on the same brain. */
  it('keeps the grant while another destination shares the host', async () => {
    install({}, ['https://eddie.example/*']);
    const { releaseHostAccess } = await load();
    await releaseHostAccess(blob({ target: 'private' }), [
      { blob: blob({ target: 'work' }), label: '', grabFolder: '' },
    ]);
    expect(fake.removed).toEqual([]);
  });

  it('hands it back when nothing left needs it', async () => {
    install({}, ['https://eddie.example/*']);
    const { releaseHostAccess } = await load();
    await releaseHostAccess(blob(), [
      { blob: blob({ brainUrl: 'https://other.example' }), label: '', grabFolder: '' },
    ]);
    expect(fake.removed).toEqual([['https://eddie.example/*']]);
  });

  /** Different ports are the same host to a match pattern — see originOf. */
  it('treats two ports on one host as one grant', async () => {
    install({}, ['http://localhost/*']);
    const { releaseHostAccess } = await load();
    await releaseHostAccess(blob({ brainUrl: 'http://localhost:9901' }), [
      { blob: blob({ brainUrl: 'http://localhost:9902' }), label: '', grabFolder: '' },
    ]);
    expect(fake.removed).toEqual([]);
  });
});
