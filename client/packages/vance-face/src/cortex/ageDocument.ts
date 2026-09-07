import {
  AgeError,
  AgeNoKeyMatchError,
  decryptArmored,
  encryptArmored,
  encryptArmoredWithPassphrase,
  identityToRecipientOrFail,
  isAgeDocument,
  looksArmored,
  splitAgePath,
} from '@vance/age';
import { readKindFromBody } from '@vance/shared';

import type { AgeTabState, CortexDocument } from './types';

/**
 * The age document transform — the one place where ciphertext becomes a
 * tab and a tab becomes ciphertext (planning/age-encryption.md §5.1).
 *
 * <p>Model: while a tab is unlocked, its {@code kind} / {@code mimeType}
 * carry the <b>inner</b> (decrypted) values so the normal binding
 * resolution dispatches to the inner editor — a decrypted workpage opens
 * in the block editor, a decrypted markdown gets its preview toggle. The
 * server-visible age markers move to {@code file.age}. While locked, the
 * tab keeps the age markers (so the registry's {@code age} entry renders
 * the unlock view) and {@code inlineText} holds the armored body for its
 * cipher preview.
 */

/** Inner mime by the double-extension hint ({@code bericht.md.age} → {@code md}). Mirrors the server's mimeFromPath text subset. */
const INNER_MIME_BY_EXT: Record<string, string> = {
  md: 'text/markdown',
  markdown: 'text/markdown',
  txt: 'text/plain',
  yaml: 'application/yaml',
  yml: 'application/yaml',
  json: 'application/json',
  xml: 'application/xml',
  html: 'text/html',
  htm: 'text/html',
  css: 'text/css',
  csv: 'text/csv',
  js: 'text/javascript',
  mjs: 'text/javascript',
  cjs: 'text/javascript',
  mjsh: 'text/javascript',
  ts: 'text/typescript',
  tsx: 'text/typescript',
  py: 'text/x-python',
  sh: 'text/x-shellscript',
  bash: 'text/x-shellscript',
  r: 'text/x-r',
  java: 'text/x-java',
  sql: 'application/sql',
  tex: 'text/x-tex',
  sty: 'text/x-tex',
  cls: 'text/x-tex',
};

/** Inner kind + mime of a decrypted body — the extension hint plus the body's own declaration. */
export function deriveInnerDocument(
  path: string,
  plaintext: string,
): { kind: string | null; mime: string } {
  const info = splitAgePath(path);
  const mime = (info && INNER_MIME_BY_EXT[info.innerExtension]) || 'text/plain';
  // The plaintext is a perfectly ordinary document body: front matter and
  // $meta work exactly as on unencrypted documents.
  return { kind: readKindFromBody(plaintext, mime), mime };
}

/**
 * Decrypt {@code armored} and project the inner document onto {@code file}.
 * No-op for non-age documents. With no fitting key the tab is locked:
 * kind/mime/inlineText keep the age markers / armored body, and the error
 * says which of the two failure shapes it is. Identities are tried before
 * passphrases (cheap before expensive), and the winner is remembered so
 * the save path can re-encrypt with the same secret when no identity exists.
 */
export async function transformAgeTab(
  file: CortexDocument,
  armored: string,
  secrets: { identities: string[]; passphrases: string[] },
): Promise<void> {
  if (!isAgeDocument(file.kind, file.mimeType)) return;
  const age: AgeTabState = {
    armored,
    locked: true,
    error: null,
    originalKind: file.kind ?? null,
    originalMimeType: file.mimeType ?? null,
    unlockedPassphraseIndex: null,
  };
  file.age = age;
  if (!looksArmored(armored)) {
    age.error = 'notArmored';
    return;
  }
  for (const identity of secrets.identities) {
    try {
      const plaintext = await decryptArmored(armored, { identities: [identity] });
      applyUnlocked(file, age, plaintext, null);
      return;
    } catch (e) {
      if (!(e instanceof AgeNoKeyMatchError)) throw e;
    }
  }
  for (let i = 0; i < secrets.passphrases.length; i++) {
    try {
      const plaintext = await decryptArmored(armored, {
        passphrases: [secrets.passphrases[i]],
      });
      applyUnlocked(file, age, plaintext, i);
      return;
    } catch (e) {
      if (!(e instanceof AgeNoKeyMatchError)) throw e;
    }
  }
  age.error = 'noKey';
}

function applyUnlocked(
  file: CortexDocument,
  age: AgeTabState,
  plaintext: string,
  passphraseIndex: number | null,
): void {
  const inner = deriveInnerDocument(file.path, plaintext);
  file.inlineText = plaintext;
  file.baselineInlineText = plaintext;
  file.kind = inner.kind;
  file.mimeType = inner.mime;
  file.dirty = false;
  age.locked = false;
  age.error = null;
  age.unlockedPassphraseIndex = passphraseIndex;
}

/**
 * The save-side twin of {@link transformAgeTab}: encrypt a tab's plaintext
 * back to armored ciphertext. Identities win when present — the document is
 * re-encrypted to every held identity's recipient, which is v1's sharing
 * mechanism. Without identities the passphrase that unlocked re-encrypts
 * (scrypt cost applies per manual save; see planning §1.4).
 */
export async function encryptTabBody(
  plaintext: string,
  secrets: { identities: string[]; passphrases: string[] },
  unlockedPassphraseIndex: number | null,
): Promise<string> {
  if (secrets.identities.length > 0) {
    const recipients = await Promise.all(
      secrets.identities.map((identity) => identityToRecipientOrFail(identity)),
    );
    return encryptArmored(plaintext, recipients);
  }
  const passphrase =
    secrets.passphrases[unlockedPassphraseIndex ?? 0] ?? secrets.passphrases[0];
  if (passphrase) {
    return encryptArmoredWithPassphrase(plaintext, passphrase);
  }
  throw new AgeError('No age identity or passphrase available to encrypt with');
}
