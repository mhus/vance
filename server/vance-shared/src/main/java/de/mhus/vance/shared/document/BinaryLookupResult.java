package de.mhus.vance.shared.document;

/**
 * Result of a cascade lookup that keeps the content as bytes — for files that are not text (images, fonts), where
 * {@link LookupResult} would have decoded them as UTF-8 and ruined them.
 *
 * @param path    normalized path the lookup matched (without leading slash)
 * @param content the bytes of the document
 * @param source  which cascade layer the value came from
 */
public record BinaryLookupResult(String path, byte[] content, LookupResult.Source source) {}
