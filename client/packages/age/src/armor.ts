import { armor } from 'age-encryption';

import { AGE_ARMOR_BEGIN, ARMOR_PROBE_LIMIT } from './constants';
import { AgeArmorError } from './errors';

/**
 * Whether {@code text} opens with the age armor begin line. A shape check
 * only — no crypto, no key material; the client-side mirror of the Java
 * `AgeDocumentKind.looksArmored`. Used to decide how to render a document
 * whose kind row got lost, and as a fast guard before `decode`.
 */
export function looksArmored(text: string | null | undefined): boolean {
  if (!text) return false;
  const head = text.length > ARMOR_PROBE_LIMIT ? text.slice(0, ARMOR_PROBE_LIMIT) : text;
  return head.trim().startsWith(AGE_ARMOR_BEGIN);
}

/** Wrap an encrypted file (binary age) in the PEM-like ASCII armor. */
export function encodeArmor(bytes: Uint8Array): string {
  return armor.encode(bytes);
}

/**
 * Strip the ASCII armor back to the raw encrypted file. Surfaces parse
 * failures as {@link AgeArmorError} instead of the library's raw throw —
 * callers render that as "not an age document", never as a stack trace.
 */
export function decodeArmor(text: string): Uint8Array {
  try {
    return armor.decode(text);
  } catch (cause) {
    throw new AgeArmorError('Not a decodable age ASCII armor document', { cause });
  }
}
