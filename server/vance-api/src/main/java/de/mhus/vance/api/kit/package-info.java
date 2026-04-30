/**
 * Wire-contract DTOs for the kit subsystem.
 *
 * <p>Kits are git-repo backed bundles of documents, settings, and
 * server-tools that can be installed into projects. See
 * {@code specification/kits.md} for the full subsystem spec.
 *
 * <p>Served by {@code de.mhus.vance.brain.kit.KitController}
 * under {@code /brain/{tenant}/admin/kits/...}.
 */
@NullMarked
package de.mhus.vance.api.kit;

import org.jspecify.annotations.NullMarked;
