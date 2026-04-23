/**
 * User domain — local accounts inside a tenant.
 *
 * <p>A user is scoped to exactly one tenant; {@code (tenantId, name)} is the
 * uniqueness constraint. The password hash lives on the document (nullable for
 * future OAuth-only users); plaintext passwords never touch this package —
 * callers hash upstream.
 *
 * <p>Colocated: {@link de.mhus.vance.shared.user.UserDocument},
 * {@link de.mhus.vance.shared.user.UserRepository} (package-private) and
 * {@link de.mhus.vance.shared.user.UserService} live in this package so the
 * repository stays internal.
 */
@NullMarked
package de.mhus.vance.shared.user;

import org.jspecify.annotations.NullMarked;
