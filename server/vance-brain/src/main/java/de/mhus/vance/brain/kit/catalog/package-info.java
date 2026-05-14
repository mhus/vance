/**
 * Catalog support for the project-kits picker. Scans a remote kits
 * repository (default {@code https://github.com/mhus/vance-kits.git})
 * and produces a {@link de.mhus.vance.api.kit.ProjectKitsCatalogDto}
 * suitable for persistence via
 * {@link de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService}.
 *
 * <p>See {@code specification/project-kits-catalog.md}.
 */
@NullMarked
package de.mhus.vance.brain.kit.catalog;

import org.jspecify.annotations.NullMarked;
