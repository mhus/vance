import { Decrypter, Encrypter } from 'age-encryption';

import { decodeArmor, encodeArmor } from './armor';
import { AgeError, AgeNoKeyMatchError } from './errors';
import { isRecipient } from './keys';

/** Everything the decrypter may try on one document — all secrets are tried, first match wins. */
export interface AgeSecrets {
  readonly identities?: readonly string[];
  readonly passphrases?: readonly string[];
}

/**
 * Encrypt UTF-8 plaintext to one or more X25519 recipients, armored output.
 * Every listed recipient will be able to decrypt — that is the sharing
 * mechanism of v1 (no server-side key directory yet).
 */
export async function encryptArmored(
  plaintext: string,
  recipients: readonly string[],
): Promise<string> {
  const clean = [...new Set(recipients.map((r) => r.trim()))].filter(Boolean);
  if (clean.length === 0) {
    throw new AgeError('At least one recipient is required to encrypt');
  }
  const invalid = clean.filter((r) => !isRecipient(r));
  if (invalid.length > 0) {
    throw new AgeError(`Not well-formed age recipient(s): ${invalid.join(', ')}`);
  }
  const encrypter = new Encrypter();
  for (const recipient of clean) {
    encrypter.addRecipient(recipient);
  }
  return encodeArmor(await encrypter.encrypt(plaintext));
}

/**
 * Encrypt UTF-8 plaintext with a passphrase, armored output. Deliberately the
 * slower path: every encryption runs the scrypt key derivation (work factor
 * 18 by default, ~seconds), so editing sessions should prefer identities —
 * see planning/age-encryption.md §1.4.
 */
export async function encryptArmoredWithPassphrase(
  plaintext: string,
  passphrase: string,
): Promise<string> {
  if (!passphrase) {
    throw new AgeError('A passphrase is required to encrypt');
  }
  const encrypter = new Encrypter();
  encrypter.setPassphrase(passphrase);
  return encodeArmor(await encrypter.encrypt(plaintext));
}

/**
 * Decrypt an armored document with every provided secret tried in parallel
 * (age files may carry stanzas for several keys). Returns the UTF-8
 * plaintext — v1's inner documents are text formats by scope decision.
 *
 * Throws {@link AgeNoKeyMatchError} when the file is valid but no secret
 * matched — the UI answers that with "import the right key", never with
 * "file is broken".
 */
export async function decryptArmored(armored: string, secrets: AgeSecrets): Promise<string> {
  const bytes = decodeArmor(armored);

  const identities = (secrets.identities ?? []).map((i) => i.trim()).filter(Boolean);
  const passphrases = (secrets.passphrases ?? []).filter((p) => p.length > 0);
  if (identities.length === 0 && passphrases.length === 0) {
    throw new AgeNoKeyMatchError('No age identity or passphrase was provided');
  }

  const decrypter = new Decrypter();
  for (const identity of identities) {
    decrypter.addIdentity(identity);
  }
  for (const passphrase of passphrases) {
    decrypter.addPassphrase(passphrase);
  }

  try {
    return await decrypter.decrypt(bytes, 'text');
  } catch (cause) {
    throw new AgeNoKeyMatchError(
      'None of the provided identities or passphrases could decrypt the document',
      { cause },
    );
  }
}
