/**
 * Tenant domain.
 *
 * The tenant is the top-level isolation scope — each tenant has its own JWT signing
 * key (see {@code de.mhus.vance.shared.keystore}) and, later, its own projects,
 * engines and credentials. In v1 a single {@code default} tenant is created on
 * startup; multi-tenant deployments come later.
 *
 * Co-located by intent: service, repository and document live in this package so
 * the repository can stay package-private and only the {@link de.mhus.vance.shared.tenant.TenantService}
 * exposes tenant data to the rest of the server.
 */
@NullMarked
package de.mhus.vance.shared.tenant;

import org.jspecify.annotations.NullMarked;
