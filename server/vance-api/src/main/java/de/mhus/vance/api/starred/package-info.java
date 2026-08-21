/**
 * Wire-contract types for starred documents — the per-user, cross-project list
 * of pinned documents that feeds both the landing-page tiles and the technical
 * "which document takes a link?" lookup.
 *
 * <p>Store side ({@code StarredService}, {@code StarredCodec}, the
 * {@code vance-starred} kind) lives in {@code de.mhus.vance.shared.starred}.
 */
@NullMarked
package de.mhus.vance.api.starred;

import org.jspecify.annotations.NullMarked;
