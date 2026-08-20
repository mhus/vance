/**
 * Facets — the dimension a reader filters a foreign source by (place of
 * origin, topic, …), shared by the two contracts that need one.
 *
 * <p>Deliberately its own package rather than living in
 * {@code de.mhus.vance.toolpack.feed}: both Centauri (streams) and Zarniwoop
 * (search) offer the same filter, and a source that serves both — Hrafnagud
 * does — declares it once. Putting the type in one of the two contract
 * packages would make the other depend on it, and those two are meant not to
 * know each other.
 *
 * <p>A facet is exactly two things: a <b>declaration</b> in a source's
 * capabilities and a <b>field in the request</b>. It is deliberately not an
 * output concept — no hit and no stream entry carries facet values. See
 * {@code planning/centauri-facets.md} §3.3 for why the output half was
 * dropped.
 *
 * <p>{@code @NullMarked} package — references are non-null by default unless
 * annotated {@code @Nullable}. See CLAUDE.md "Null-Safety mit JSpecify".
 */
@NullMarked
package de.mhus.vance.toolpack.facet;

import org.jspecify.annotations.NullMarked;
