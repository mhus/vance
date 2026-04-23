/**
 * Document domain — the one place where anything a user drops into a project
 * gets stored: notes, PDFs, images, CSVs. Mime type is the discriminator, not
 * the class.
 *
 * <p>A document carries two organizing axes:
 * <ul>
 *   <li>a single {@code path} (e.g. {@code notes/thesis/chapter-1.md}) that
 *       forms a <em>virtual</em> folder tree — folders are never persisted,
 *       they're derived from the paths in use</li>
 *   <li>any number of {@code tags} for the orthogonal slice</li>
 * </ul>
 *
 * <p>Small text payloads (below {@code vance.document.inline-threshold}) land
 * directly in {@code DocumentDocument.inlineText}; everything else streams
 * through {@link de.mhus.vance.shared.storage.StorageService}.
 *
 * <p>Colocated: document + package-private repository + service.
 */
@NullMarked
package de.mhus.vance.shared.document;

import org.jspecify.annotations.NullMarked;
