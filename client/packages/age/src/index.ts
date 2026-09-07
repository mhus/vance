export {
  AGE_ARMOR_BEGIN,
  AGE_FILE_EXTENSION,
  AGE_KIND,
  AGE_MIME_TYPE,
  ARMOR_PROBE_LIMIT,
} from './constants';
export {
  AgeArmorError,
  AgeError,
  AgeKeyFormatError,
  AgeNoKeyMatchError,
} from './errors';
export { decodeArmor, encodeArmor, looksArmored } from './armor';
export {
  extractIdentity,
  extractRecipient,
  generateKeyPair,
  identityToRecipientOrFail,
  isIdentity,
  isRecipient,
} from './keys';
export type { AgeKeyPair } from './keys';
export { decryptArmored, encryptArmored, encryptArmoredWithPassphrase } from './cipher';
export type { AgeSecrets } from './cipher';
export { hasAgeExtension, isAgeDocument, splitAgePath } from './path';
export type { AgePathInfo } from './path';
