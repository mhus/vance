/**
 * Persistence for the system-wide addon registry. The
 * {@link de.mhus.vance.shared.addon.AddonService} owns the
 * {@code addons} collection; everything else (bootstrap seeding,
 * REST controllers, future install API) talks to that service, not
 * the repository directly.
 *
 * <p>Spec: {@code specification/addon-system.md}.
 */
@NullMarked
package de.mhus.vance.shared.addon;

import org.jspecify.annotations.NullMarked;
