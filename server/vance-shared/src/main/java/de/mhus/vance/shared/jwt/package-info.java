/**
 * JWT creation and verification on top of {@link de.mhus.vance.shared.keystore.KeyService}.
 *
 * Uses one signing key per tenant (purpose {@code jwt-signing}). Tokens carry
 * the username as {@code sub} and the tenant as {@code tid}. The signing key
 * itself is bootstrapped by {@link de.mhus.vance.shared.tenant.TenantService}
 * when a tenant is created — no separate initializer lives here.
 */
@NullMarked
package de.mhus.vance.shared.jwt;

import org.jspecify.annotations.NullMarked;
