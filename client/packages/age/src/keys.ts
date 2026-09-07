import { generateX25519Identity, identityToRecipient } from 'age-encryption';

import { AgeKeyFormatError } from './errors';

/** Bech32 data charset (lower case) — excludes `1`, `b`, `i`, `o`. */
const BECH32_LOWER = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';

const BECH32_UPPER = BECH32_LOWER.toUpperCase();

/** `AGE-SECRET-KEY-1` + 52 data chars + 6 checksum = 74. */
const IDENTITY_RE = new RegExp(`^AGE-SECRET-KEY-1[${BECH32_UPPER}]{58}$`);

/** `age1` + 52 data chars + 6 checksum = 62. */
const RECIPIENT_RE = new RegExp(`^age1[${BECH32_LOWER}]{58}$`);

/** Unanchored searches for extracting keys out of whole keygen / recipients files. */
const IDENTITY_IN_TEXT_RE = new RegExp(`AGE-SECRET-KEY-1[${BECH32_UPPER}]{58}`);
const RECIPIENT_IN_TEXT_RE = new RegExp(`age1[${BECH32_LOWER}]{58}`);

/**
 * Whether {@code value} is a well-formed X25519 secret key
 * (`AGE-SECRET-KEY-1...`). Strictly the standard v1 shape — the post-quantum
 * variants typage can generate are deliberately not part of Vance's v1
 * surface (they would break interop with the reference `age` CLI).
 */
export function isIdentity(value: string): boolean {
  return IDENTITY_RE.test(value.trim());
}

/** Whether {@code value} is a well-formed X25519 recipient (`age1...`). */
export function isRecipient(value: string): boolean {
  return RECIPIENT_RE.test(value.trim());
}

/**
 * The first `AGE-SECRET-KEY-1` key in pasted or imported text. Keygen files
 * carry `# public key: age1...` comment lines above the secret — the
 * unlock UI takes whole files, so extraction happens here, not at every
 * call site.
 */
export function extractIdentity(text: string): string | null {
  return IDENTITY_IN_TEXT_RE.exec(text)?.[0] ?? null;
}

/**
 * The first `age1...` recipient in pasted or imported text — a keygen
 * file's `Public key: age1...` line, a recipients file, or a bare key.
 */
export function extractRecipient(text: string): string | null {
  return RECIPIENT_IN_TEXT_RE.exec(text)?.[0] ?? null;
}

export interface AgeKeyPair {
  identity: string;
  recipient: string;
}

/** Generate a fresh X25519 identity pair — the "create a key" path of the unlock UI and the Encrypt action. */
export async function generateKeyPair(): Promise<AgeKeyPair> {
  const identity = await generateX25519Identity();
  return { identity, recipient: await identityToRecipient(identity) };
}

/** Derive the recipient of an identity — used to show what a key unlocks and to encrypt to one's own key. */
export async function identityToRecipientOrFail(identity: string): Promise<string> {
  const trimmed = identity.trim();
  if (!isIdentity(trimmed)) {
    throw new AgeKeyFormatError(
      'Not a well-formed age identity — expected AGE-SECRET-KEY-1... (X25519)',
    );
  }
  try {
    return await identityToRecipient(trimmed);
  } catch (cause) {
    throw new AgeKeyFormatError('The age identity is malformed (bech32 checksum failed)', {
      cause,
    });
  }
}
