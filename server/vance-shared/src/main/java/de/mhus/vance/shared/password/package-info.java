/**
 * Password hashing and verification.
 *
 * <p>Thin wrapper around Spring Security's {@code PasswordEncoder} so callers
 * don't need to know which hash algorithm is in use. v1 is BCrypt; migrating
 * later (e.g. to Argon2) is a one-line change in
 * {@link de.mhus.vance.shared.password.PasswordService}.
 */
@NullMarked
package de.mhus.vance.shared.password;

import org.jspecify.annotations.NullMarked;
