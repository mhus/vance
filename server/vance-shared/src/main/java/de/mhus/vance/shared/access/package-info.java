/**
 * Base access-control layer shared by all Vance server components.
 *
 * {@link de.mhus.vance.shared.access.AccessFilterBase} is a servlet filter that
 * validates the {@code Authorization: Bearer &lt;jwt&gt;} header via
 * {@link de.mhus.vance.shared.jwt.JwtService} and attaches the verified
 * {@link de.mhus.vance.shared.jwt.VanceJwtClaims} to the request. Concrete
 * services (e.g. the Brain) extend it and decide which paths require
 * authentication. Cookies are not used — all clients send the JWT as bearer.
 */
@NullMarked
package de.mhus.vance.shared.access;

import org.jspecify.annotations.NullMarked;
