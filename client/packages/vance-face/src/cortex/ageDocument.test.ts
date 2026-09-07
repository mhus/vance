import { describe, expect, it } from 'vitest';

import {
  AgeError,
  decryptArmored,
  encryptArmored,
  encryptArmoredWithPassphrase,
  generateKeyPair,
} from '@vance/age';

import { deriveInnerDocument, encryptTabBody, transformAgeTab } from './ageDocument';
import type { CortexDocument } from './types';

function doc(overrides: Partial<CortexDocument> = {}): CortexDocument {
  return {
    id: 'd1',
    path: 'documents/secret.md.age',
    name: 'secret.md.age',
    mimeType: 'application/age+armored',
    kind: 'age',
    inlineText: '',
    dirty: false,
    baselineInlineText: '',
    ...overrides,
  } as CortexDocument;
}

describe('deriveInnerDocument', () => {
  it('reads the inner kind from the decrypted front matter', () => {
    const inner = deriveInnerDocument(
      'documents/bericht.md.age',
      '---\nkind: workpage\n---\n\n# Geheim\n',
    );
    expect(inner.kind).toBe('workpage');
    expect(inner.mime).toBe('text/markdown');
  });

  it('maps the double extension to the inner mime', () => {
    expect(deriveInnerDocument('x/a.yaml.age', 'kind: x\n')).toMatchObject({
      kind: null,
      mime: 'application/yaml',
    });
    expect(deriveInnerDocument('x/a.json.age', '{}')).toMatchObject({
      mime: 'application/json',
    });
  });

  it('falls back to plain text without an inner extension', () => {
    expect(deriveInnerDocument('x/secret.age', 'just prose')).toEqual({
      kind: null,
      mime: 'text/plain',
    });
  });
});

describe('transformAgeTab', () => {
  it('unlocks with a fitting identity and projects the inner document', async () => {
    const pair = await generateKeyPair();
    const armored = await encryptArmored('# secret\n', [pair.recipient]);
    const file = doc({ inlineText: armored, baselineInlineText: armored });

    await transformAgeTab(file, armored, { identities: [pair.identity], passphrases: [] });

    expect(file.age).toBeDefined();
    expect(file.age?.locked).toBe(false);
    expect(file.age?.armored).toBe(armored);
    expect(file.age?.originalKind).toBe('age');
    expect(file.age?.originalMimeType).toBe('application/age+armored');
    expect(file.age?.unlockedPassphraseIndex).toBeNull();
    expect(file.inlineText).toBe('# secret\n');
    expect(file.baselineInlineText).toBe('# secret\n');
    expect(file.kind).toBeNull();
    expect(file.mimeType).toBe('text/markdown');
    expect(file.dirty).toBe(false);
  });

  it('unlocks with a fitting passphrase and remembers the index', { timeout: 60_000 }, async () => {
    const armored = await encryptArmoredWithPassphrase('pw secret', 'correct-horse');
    const file = doc({ inlineText: armored });

    await transformAgeTab(file, armored, {
      identities: [],
      passphrases: ['wrong-first', 'correct-horse'],
    });

    expect(file.age?.locked).toBe(false);
    expect(file.age?.unlockedPassphraseIndex).toBe(1);
    expect(file.inlineText).toBe('pw secret');
  });

  it('locks with noKey when no secret fits', async () => {
    const pair = await generateKeyPair();
    const armored = await encryptArmored('x', [pair.recipient]);
    const stranger = await generateKeyPair();
    const file = doc({ inlineText: armored });

    await transformAgeTab(file, armored, { identities: [stranger.identity], passphrases: [] });

    expect(file.age?.locked).toBe(true);
    expect(file.age?.error).toBe('noKey');
    // The armored body stays visible for the locked view's cipher preview.
    expect(file.inlineText).toBe(armored);
    expect(file.kind).toBe('age');
  });

  it('locks with notArmored when the stored body is not armor', async () => {
    const file = doc({ inlineText: '# plaintext under an age mime\n' });

    await transformAgeTab(file, file.inlineText, { identities: [], passphrases: [] });

    expect(file.age?.locked).toBe(true);
    expect(file.age?.error).toBe('notArmored');
  });

  it('is a no-op for ordinary documents', async () => {
    const file = doc({ kind: 'workpage', mimeType: 'text/markdown', inlineText: '# hi\n' });
    await transformAgeTab(file, file.inlineText, { identities: [], passphrases: [] });
    expect(file.age).toBeUndefined();
    expect(file.kind).toBe('workpage');
  });
});

describe('encryptTabBody', () => {
  it('encrypts to every held identity', async () => {
    const a = await generateKeyPair();
    const b = await generateKeyPair();

    const armored = await encryptTabBody('plain', {
      identities: [a.identity, b.identity],
      passphrases: [],
    }, null);

    await expect(decryptArmored(armored, { identities: [a.identity] })).resolves.toBe('plain');
    await expect(decryptArmored(armored, { identities: [b.identity] })).resolves.toBe('plain');
  });

  it('falls back to the unlocking passphrase when no identity exists', { timeout: 60_000 }, async () => {
    const armored = await encryptTabBody('plain', {
      identities: [],
      passphrases: ['other', 'the-one'],
    }, 1);

    await expect(decryptArmored(armored, { passphrases: ['the-one'] })).resolves.toBe('plain');
  });

  it('throws without any secret', async () => {
    await expect(
      encryptTabBody('plain', { identities: [], passphrases: [] }, null),
    ).rejects.toThrow(AgeError);
  });
});
