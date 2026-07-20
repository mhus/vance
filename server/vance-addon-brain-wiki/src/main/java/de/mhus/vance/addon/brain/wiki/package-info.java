/**
 * Wiki addon — first-party Vance application.
 *
 * <p>Implements {@code app: wiki}: a folder-as-wiki container of
 * {@code kind: workpage} pages, addressed by name through
 * {@code [[Wikilink]]}s, with a backlink graph, spaces (sub-folders) and
 * generated {@code _index.md} pages per space. Unlike the workbook addon
 * (a curated tree), the wiki is a name-addressed link graph — links are
 * the structure.
 *
 * <p>The wiki <b>reuses</b> the {@code kind: workpage} document type owned
 * by the workbook addon; it does not re-register the workpage services /
 * tools. Pages are plain Markdown documents with a
 * {@code $meta.kind: workpage} header, written directly through
 * {@link de.mhus.vance.shared.document.DocumentService}.
 *
 * <p>Loaded by Spring Boot via {@code META-INF/spring/...AutoConfiguration.imports}.
 */
@NullMarked
package de.mhus.vance.addon.brain.wiki;

import org.jspecify.annotations.NullMarked;
