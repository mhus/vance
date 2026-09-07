/**
 * Contract constants for age-encrypted documents — the client-side twin of
 * the Java `AgeDocumentKind` (vance-api). Both sides must stay in sync;
 * there is no code generation between them because none of these are DTOs.
 */
export const AGE_KIND = 'age';

export const AGE_MIME_TYPE = 'application/age+armored';

export const AGE_FILE_EXTENSION = 'age';

/** First line of the age v1 ASCII armor. */
export const AGE_ARMOR_BEGIN = '-----BEGIN AGE ENCRYPTED FILE-----';

/**
 * Longest prefix worth inspecting for the armor begin line — same probe
 * bound as the Java side (`AgeDocumentKind`).
 */
export const ARMOR_PROBE_LIMIT = 128;
