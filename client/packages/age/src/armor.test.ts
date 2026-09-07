import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

import { AGE_ARMOR_BEGIN } from './constants';
import { decodeArmor, encodeArmor, looksArmored } from './armor';
import { AgeArmorError } from './errors';

const fixtureArmored = readFileSync(
  new URL('./fixtures/hello-x25519.age', import.meta.url),
  'utf8',
);

describe('looksArmored', () => {
  it('recognizes the fixture', () => {
    expect(looksArmored(fixtureArmored)).toBe(true);
  });

  it('tolerates leading whitespace', () => {
    expect(looksArmored(`\n  ${AGE_ARMOR_BEGIN}\nYWdl…`)).toBe(true);
  });

  it('rejects prose, armor buried in content, and empties', () => {
    expect(looksArmored('# just markdown')).toBe(false);
    expect(looksArmored(`intro text\n${fixtureArmored}`)).toBe(false);
    expect(looksArmored('')).toBe(false);
    expect(looksArmored(null as unknown as string)).toBe(false);
  });

  it('does not read past the probe limit', () => {
    // 200 chars of prose in front of the marker — outside the probe, not
    // armor, same answer the Java side gives.
    expect(looksArmored('a'.repeat(200) + AGE_ARMOR_BEGIN)).toBe(false);
  });
});

describe('encodeArmor / decodeArmor', () => {
  it('round-trips arbitrary bytes', () => {
    const bytes = new Uint8Array(300).map((_, i) => i % 251);
    const armored = encodeArmor(bytes);
    expect(armored.startsWith(AGE_ARMOR_BEGIN)).toBe(true);
    expect(looksArmored(armored)).toBe(true);
    expect(Buffer.from(decodeArmor(armored)).equals(Buffer.from(bytes))).toBe(true);
  });

  it('decodes the reference-CLI fixture', () => {
    // Decode alone proves nothing about the keys — but a parse failure here
    // would mean the Go CLI produced armor typage cannot read at all.
    expect(decodeArmor(fixtureArmored).length).toBeGreaterThan(0);
  });

  it('tolerates surrounding whitespace and CRLF line endings', () => {
    // Documents accumulate trailing newlines and Windows editors CRLF —
    // the armor decoder must not care.
    const crlf = fixtureArmored.replace(/\n/g, '\r\n');
    expect(Buffer.from(decodeArmor(`\n${crlf}\n\n`)).equals(
      Buffer.from(decodeArmor(fixtureArmored)),
    )).toBe(true);
  });

  it('rejects non-armor text with AgeArmorError', () => {
    expect(() => decodeArmor('# not an age file')).toThrow(AgeArmorError);
  });

  it('rejects truncated armor with AgeArmorError', () => {
    expect(() => decodeArmor(fixtureArmored.split('-----END')[0])).toThrow(AgeArmorError);
  });
});
