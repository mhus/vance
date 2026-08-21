/**
 * Jaglan seam in the document layer — the {@code _ext/} namespace and the
 * optional port {@code DocumentService} calls instead of
 * {@code StorageService} for mounted paths.
 *
 * <p>A mounted document has a <b>real Mongo row</b> (path, name, kind, mime,
 * size, headers, {@code lockedFor}) but <b>no {@code storageId}</b>: the
 * metadata is a shell with a TTL, the bytes are streamed from the source on
 * every read. That is what lets the existing document tooling — Cortex,
 * embeds, {@code DocumentRef}, WebDAV, the soft lock — work unchanged,
 * without copying foreign content into the brain.
 *
 * <p>What deliberately does not apply to these documents: summary, RAG
 * indexing, versioning/archives (archiving means copying bytes, which
 * contradicts the pass-through) and trash (the trash folder lives outside
 * {@code _ext/}, so moving there would break the address).
 *
 * <p>Contract and dispatcher live elsewhere:
 * {@code de.mhus.vance.toolpack.jaglan} holds the protocol SPI,
 * {@code de.mhus.vance.brain.jaglan} the dispatcher and the source factory.
 * Concept and decisions: {@code planning/jaglan-mounted-docs.md}.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.shared.document.jaglan;

import org.jspecify.annotations.NullMarked;
