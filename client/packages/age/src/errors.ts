/** Base class for every error this package throws — lets callers catch age failures without pinning internals. */
export class AgeError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'AgeError';
  }
}

/**
 * The text is not a decodable age ASCII armor. The document may be corrupt
 * or not age-encrypted at all — distinct from a wrong key.
 */
export class AgeArmorError extends AgeError {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'AgeArmorError';
  }
}

/**
 * A valid age file, but none of the provided identities or passphrases
 * unwrapped its file key. The user-facing answer is "import the right key",
 * not "file is broken".
 */
export class AgeNoKeyMatchError extends AgeError {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'AgeNoKeyMatchError';
  }
}

/** An identity or recipient string is not a well-formed age key. */
export class AgeKeyFormatError extends AgeError {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'AgeKeyFormatError';
  }
}
