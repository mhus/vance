/**
 * Typed in-memory models and codecs for the {@code kind: …} document
 * payloads — one class family per kind ({@code list}, {@code tree},
 * {@code records}, {@code sheet}, {@code mindmap}, {@code graph},
 * {@code data}). Each codec parses the on-disk body (markdown / JSON /
 * YAML where applicable) into a typed model and serialises it back
 * round-trip stable.
 *
 * <p>Server-side counterpart of the TypeScript codecs under
 * {@code packages/vance-face/src/document/*Codec.ts}. Same wire
 * format, same backward-compat rules — both sides must agree.
 *
 * <p>Specs (one per kind):
 * <ul>
 *   <li>{@code specification/doc-kind-items.md} — {@code kind: list}</li>
 *   <li>{@code specification/doc-kind-tree.md} — {@code kind: tree}</li>
 *   <li>{@code specification/doc-kind-records.md} — {@code kind: records}</li>
 *   <li>{@code specification/doc-kind-sheet.md} — {@code kind: sheet}</li>
 *   <li>{@code specification/doc-kind-mindmap.md} — {@code kind: mindmap}</li>
 *   <li>{@code specification/doc-kind-graph.md} — {@code kind: graph}</li>
 * </ul>
 *
 * <p>Hard rule: classes here are <strong>service-free</strong> — pure
 * data + parsing utilities, no Spring beans, no MongoDB, no
 * {@link de.mhus.vance.shared.document.DocumentService} dependency.
 * That keeps them callable from anywhere in the server stack
 * (HTTP layer, scheduled jobs, future CLI tools) without dragging
 * the wider Spring context along.
 */
@NullMarked
package de.mhus.vance.shared.document.kind;

import org.jspecify.annotations.NullMarked;
