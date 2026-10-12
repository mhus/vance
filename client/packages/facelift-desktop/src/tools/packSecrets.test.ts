import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

import { afterEach, describe, expect, it } from 'vitest';

import { PackSecretStore, type PackSecretCrypto } from './packSecrets';

const fakeCrypto: PackSecretCrypto = {
  encrypt: (value: string) => Buffer.from(`enc:${value}`, 'utf8'),
  decrypt: (blob: Buffer) => {
    const s = blob.toString('utf8');
    if (!s.startsWith('enc:')) throw new Error('undecryptable');
    return s.slice(4);
  },
};

let dir: string;

async function store(): Promise<PackSecretStore> {
  dir = await mkdtemp(path.join(os.tmpdir(), 'pack-secrets-'));
  return new PackSecretStore(fakeCrypto, () => dir);
}

afterEach(async () => {
  if (dir !== undefined) await rm(dir, { recursive: true, force: true });
  dir = undefined as unknown as string;
});

describe('PackSecretStore', () => {
  it('setGet_roundTrips', async () => {
    const s = await store();
    await s.set('acc-1', 'jira.token', 'hunter2');
    expect(await s.get('acc-1', 'jira.token')).toBe('hunter2');
    expect(await s.names('acc-1')).toEqual(['jira.token']);
  });

  it('values_areNotPlaintextOnDisk', async () => {
    const s = await store();
    await s.set('acc-1', 'jira.token', 'hunter2');
    const raw = await readFile(path.join(dir, 'acc-1.pack-secrets.yaml'), 'utf-8');
    expect(raw).not.toContain('hunter2');
  });

  it('clear_removesOnlyThatEntry', async () => {
    const s = await store();
    await s.set('acc-1', 'jira.token', 'a');
    await s.set('acc-1', 'gh.token', 'b');
    await s.clear('acc-1', 'jira.token');
    expect(await s.get('acc-1', 'jira.token')).toBeUndefined();
    expect(await s.get('acc-1', 'gh.token')).toBe('b');
  });

  it('undecryptableEntry_behavesLikeMissing', async () => {
    // e.g. the file was copied from another machine — no keychain match.
    const s = await store();
    await s.set('acc-1', 'jira.token', 'a');
    const other = new PackSecretStore(
      {
        encrypt: fakeCrypto.encrypt,
        decrypt: () => {
          throw new Error('keychain mismatch');
        },
      },
      () => dir,
    );
    expect(await other.get('acc-1', 'jira.token')).toBeUndefined();
  });

  it('accounts_areIsolated', async () => {
    const s = await store();
    await s.set('acc-1', 'jira.token', 'a');
    expect(await s.get('acc-2', 'jira.token')).toBeUndefined();
  });
});
