import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import {
  AgeKeyFormatError,
  extractIdentity,
  identityToRecipientOrFail,
  isIdentity,
} from '@vance/age';

/**
 * Session-held age keys — the identities and passphrases the user imported
 * to read age-encrypted documents (planning/age-encryption.md §5.2).
 *
 * <p>Memory-only by design: nothing is persisted — no localStorage, no
 * sessionStorage, no server round-trip, nothing in the URL. A browser
 * reload re-prompts; that is the price of the threat model, not an
 * oversight. Wiping is explicit via {@link lock}.
 */
export const useAgeKeyStore = defineStore('age-keys', () => {
  const identities = ref<string[]>([]);
  const passphrases = ref<string[]>([]);

  const hasSecrets = computed(
    () => identities.value.length > 0 || passphrases.value.length > 0,
  );

  /**
   * Add an identity. Accepts a bare {@code AGE-SECRET-KEY-1…} string or a
   * whole keygen file (comment lines included) — extraction happens here so
   * no call site has to care. Deduplicated.
   *
   * @throws {AgeKeyFormatError} when the text contains no well-formed identity.
   */
  function addIdentity(text: string): string {
    const identity = extractIdentity(text) ?? text.trim();
    if (!isIdentity(identity)) {
      throw new AgeKeyFormatError(
        'Not a well-formed age identity — expected AGE-SECRET-KEY-1… (X25519)',
      );
    }
    if (!identities.value.includes(identity)) {
      identities.value = [...identities.value, identity];
    }
    return identity;
  }

  function addPassphrase(passphrase: string): void {
    if (!passphrase) return;
    if (!passphrases.value.includes(passphrase)) {
      passphrases.value = [...passphrases.value, passphrase];
    }
  }

  /** Wipe every held secret — the "forget keys" action. */
  function lock(): void {
    identities.value = [];
    passphrases.value = [];
  }

  /** All held secrets in the shape @vance/age's decrypter wants. */
  function secrets(): { identities: string[]; passphrases: string[] } {
    return { identities: [...identities.value], passphrases: [...passphrases.value] };
  }

  /** Recipients of every held identity — the encryption targets on save. */
  async function recipients(): Promise<string[]> {
    return Promise.all(identities.value.map((identity) => identityToRecipientOrFail(identity)));
  }

  return {
    identities,
    passphrases,
    hasSecrets,
    addIdentity,
    addPassphrase,
    lock,
    secrets,
    recipients,
  };
});
