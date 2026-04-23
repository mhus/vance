/**
 * Brain-level access control and token minting.
 *
 * Contains:
 * <ul>
 *   <li>{@link de.mhus.vance.brain.access.BrainAccessFilter} — concrete
 *       {@link de.mhus.vance.shared.access.AccessFilterBase} deciding which paths need a JWT</li>
 *   <li>{@link de.mhus.vance.brain.access.AccessController} — REST endpoint that mints JWTs
 *       ({@code POST /{tenant}/access/{username}})</li>
 * </ul>
 *
 * Colocated so the path the controller serves and the exemption it needs in the
 * filter live next to each other.
 */
@NullMarked
package de.mhus.vance.brain.access;

import org.jspecify.annotations.NullMarked;
