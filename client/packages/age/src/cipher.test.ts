import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import { decryptArmored, encryptArmored, encryptArmoredWithPassphrase } from './cipher';
import { AgeArmorError, AgeNoKeyMatchError } from './errors';
import { extractIdentity, generateKeyPair } from './keys';

const fixtures = new URL('./fixtures/', import.meta.url);
const keygenFile = readFileSync(new URL('./test-identity.txt', fixtures), 'utf8');
const fixtureIdentity = extractIdentity(keygenFile) as string;

const PASSPHRASE_FIXTURE_TEXT =
  'Passphrase fixture for Vance — decrypt with the passphrase from the README.\n';

// The reference Go age CLI — present on dev machines (brew install age),
// possibly absent in CI. Both directions of the interop proof run where it
// exists; everything else tests against the committed fixtures anyway.
const hasAgeCli = (() => {
  try {
    execFileSync('age', ['--version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
})();

describe('decryptArmored — reference-CLI fixtures', () => {
  it('decrypts a Go-age X25519 file', async () => {
    const armored = readFileSync(new URL('./hello-x25519.age', fixtures), 'utf8');
    await expect(
      decryptArmored(armored, { identities: [fixtureIdentity] }),
    ).resolves.toBe('Hello, age fixture!\n');
  });

  it('decrypts a Go-age markdown document byte-exactly', async () => {
    const armored = readFileSync(new URL('./markdown-x25519.age', fixtures), 'utf8');
    const plaintext = readFileSync(new URL('./markdown-source.md', fixtures), 'utf8');
    await expect(
      decryptArmored(armored, { identities: [fixtureIdentity] }),
    ).resolves.toBe(plaintext);
  });

  it('decrypts a Go-age passphrase file (scrypt)', { timeout: 60_000 }, async () => {
    const armored = readFileSync(new URL('./hello-passphrase.age', fixtures), 'utf8');
    await expect(
      decryptArmored(armored, { passphrases: ['vance-fixture-passphrase-2026'] }),
    ).resolves.toBe(PASSPHRASE_FIXTURE_TEXT);
  });
});

describe('encryptArmored / decryptArmored — round trips', () => {
  it('round-trips text through identity encryption', async () => {
    const pair = await generateKeyPair();
    const text = '# Änderung 🗜\n\nwith unicode ✓\n';
    const armored = await encryptArmored(text, [pair.recipient]);
    await expect(
      decryptArmored(armored, { identities: [pair.identity] }),
    ).resolves.toBe(text);
  });

  it('round-trips the empty document', async () => {
    const pair = await generateKeyPair();
    const armored = await encryptArmored('', [pair.recipient]);
    await expect(decryptArmored(armored, { identities: [pair.identity] })).resolves.toBe('');
  });

  it('encrypts to several recipients — each can decrypt', async () => {
    const a = await generateKeyPair();
    const b = await generateKeyPair();
    const armored = await encryptArmored('shared secret', [a.recipient, b.recipient]);
    await expect(decryptArmored(armored, { identities: [a.identity] })).resolves.toBe(
      'shared secret',
    );
    await expect(decryptArmored(armored, { identities: [b.identity] })).resolves.toBe(
      'shared secret',
    );
  });

  it('tries every provided secret — order does not matter', async () => {
    const right = await generateKeyPair();
    const wrong = await generateKeyPair();
    const armored = await encryptArmored('mixed keys', [right.recipient]);
    await expect(
      decryptArmored(armored, { identities: [wrong.identity, right.identity] }),
    ).resolves.toBe('mixed keys');
    await expect(
      decryptArmored(armored, { identities: [right.identity], passphrases: ['also-wrong'] }),
    ).resolves.toBe('mixed keys');
  });

  it(
    'round-trips a passphrase document',
    { timeout: 120_000 },
    async () => {
      const armored = await encryptArmoredWithPassphrase(
        'passphrase round trip',
        'vance-test-passphrase',
      );
      await expect(decryptArmored(armored, { passphrases: ['vance-test-passphrase'] })).resolves.toBe(
        'passphrase round trip',
      );
    },
  );

  it('refuses to encrypt without recipients', async () => {
    await expect(encryptArmored('x', [])).rejects.toThrow('recipient');
    await expect(encryptArmored('x', ['age1notreal'])).rejects.toThrow('recipient');
  });
});

describe('decryptArmored — error mapping', () => {
  it('maps a valid file without matching key to AgeNoKeyMatchError', async () => {
    const armored = readFileSync(new URL('./hello-x25519.age', fixtures), 'utf8');
    const stranger = await generateKeyPair();
    await expect(
      decryptArmored(armored, { identities: [stranger.identity] }),
    ).rejects.toThrow(AgeNoKeyMatchError);
  });

  it('treats a wrong passphrase as no-match, not as broken armor', { timeout: 60_000 }, async () => {
    const armored = readFileSync(new URL('./hello-passphrase.age', fixtures), 'utf8');
    await expect(
      decryptArmored(armored, { passphrases: ['wrong-passphrase'] }),
    ).rejects.toThrow(AgeNoKeyMatchError);
  });

  it('rejects empty secrets', async () => {
    const armored = readFileSync(new URL('./hello-x25519.age', fixtures), 'utf8');
    await expect(decryptArmored(armored, {})).rejects.toThrow(AgeNoKeyMatchError);
  });

  it('surfaces non-armor text as AgeArmorError, distinct from a wrong key', async () => {
    await expect(decryptArmored('# not age', { identities: [fixtureIdentity] })).rejects.toThrow(
      AgeArmorError,
    );
  });
});

describe(
  'reverse interop — typage encrypts, reference CLI decrypts',
  { skip: !hasAgeCli && 'reference `age` CLI not installed' },
  () => {
    it('produces armor the Go age CLI can decrypt with the fixture identity', async () => {
      const dir = mkdtempSync(join(tmpdir(), 'vance-age-'));
      const file = join(dir, 'roundtrip.age');
      writeFileSync(file, await encryptArmored('reverse interop check\n', [
        // Extracted from the keygen file — the CLI reads the same file.
        readFileSync(new URL('./test-identity.txt', fixtures), 'utf8')
          .match(/age1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{58}/)![0],
      ]));
      const out = execFileSync('age', ['-d', '-i', new URL('./test-identity.txt', fixtures).pathname, file], {
        encoding: 'utf8',
      });
      expect(out).toBe('reverse interop check\n');
    });
  },
);
