import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { AgeKeyFormatError } from './errors';
import {
  extractIdentity,
  extractRecipient,
  generateKeyPair,
  identityToRecipientOrFail,
  isIdentity,
  isRecipient,
} from './keys';

const keygenFile = readFileSync(new URL('./fixtures/test-identity.txt', import.meta.url), 'utf8');
const fixtureIdentity = extractIdentity(keygenFile) as string;
const fixtureRecipient = extractRecipient(keygenFile) as string;

describe('isIdentity / isRecipient', () => {
  it('accepts the fixture key pair', () => {
    expect(fixtureIdentity).toBeTruthy();
    expect(fixtureRecipient).toBeTruthy();
    expect(isIdentity(fixtureIdentity)).toBe(true);
    expect(isRecipient(fixtureRecipient)).toBe(true);
  });

  it('is strict about shape', () => {
    // Cross-wise: a recipient is not an identity and vice versa.
    expect(isIdentity(fixtureRecipient)).toBe(false);
    expect(isRecipient(fixtureIdentity)).toBe(false);
    // Post-quantum variants are deliberately out of v1 scope.
    expect(isIdentity('AGE-SECRET-KEY-PQ1' + fixtureIdentity.slice(16))).toBe(false);
    expect(isIdentity('AGE-SECRET-KEY-1NOT-A-REAL-KEY')).toBe(false);
    expect(isIdentity('')).toBe(false);
    expect(isRecipient('age1notarealkey')).toBe(false);
  });

  it('trims before checking', () => {
    expect(isIdentity(`  ${fixtureIdentity}\n`)).toBe(true);
    expect(isRecipient(`\t${fixtureRecipient}`)).toBe(true);
  });
});

describe('extractIdentity / extractRecipient', () => {
  it('finds keys inside a whole keygen file', () => {
    // The file carries a "Public key: age1…" line and comments above the
    // secret — exactly what a user pastes after running age-keygen.
    expect(extractIdentity(keygenFile)).toBe(fixtureIdentity);
    expect(extractRecipient(keygenFile)).toBe(fixtureRecipient);
  });

  it('returns null for text without keys', () => {
    expect(extractIdentity('# nothing here\nage1 nope')).toBeNull();
    expect(extractRecipient('AGE-SECRET-KEY-1WRONG')).toBeNull();
  });
});

describe('generateKeyPair / identityToRecipientOrFail', () => {
  it('produces a working pair', async () => {
    const pair = await generateKeyPair();
    expect(isIdentity(pair.identity)).toBe(true);
    expect(isRecipient(pair.recipient)).toBe(true);
    await expect(identityToRecipientOrFail(pair.identity)).resolves.toBe(pair.recipient);
  });

  it('rejects malformed identities', async () => {
    await expect(identityToRecipientOrFail('nonsense')).rejects.toThrow(AgeKeyFormatError);
  });
});
