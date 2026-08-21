/**
 * Jaglan mount data shapes — the records that travel between the mount
 * contract in {@code de.mhus.vance.toolpack.jaglan} and the document layer
 * in {@code de.mhus.vance.shared.document.jaglan}.
 *
 * <p>They live in {@code vance-api} because that is the only module both
 * sides can see: {@code vance-shared} does not depend on
 * {@code vance-toolpack} (and must not — the toolpack is the pure-Java
 * tool/contract layer, shared is the Spring/MongoDB layer). Putting these
 * two records here avoids a parallel type on each side of the port.
 *
 * <p>Paths in this package are always <b>relative to the mount root</b>,
 * never the {@code _ext/<mount>/...} document path. A source knows nothing
 * about Vance's namespace, and keeping it that way means the {@code _ext}
 * convention can change without touching a single protocol implementation.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.api.mount;

import org.jspecify.annotations.NullMarked;
