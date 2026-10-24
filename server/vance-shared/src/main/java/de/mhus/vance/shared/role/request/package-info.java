/**
 * Role requests: role changes an agent asked for but must not perform. A
 * request is inert until a human with authority over the scope approves it
 * through the inbox; the approval runs in the Brain
 * ({@code de.mhus.vance.brain.roles.RoleRequestEffect}) through
 * {@link de.mhus.vance.shared.role.RoleAdministrationService}. This package
 * only stores requests and lets them lapse. See
 * {@code planning/permission-request-inbox.md}.
 */
@NullMarked
package de.mhus.vance.shared.role.request;

import org.jspecify.annotations.NullMarked;
