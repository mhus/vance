/**
 * Brain-side glue around {@link de.mhus.vance.shared.permission.PermissionService}.
 *
 * <p>The shared module owns the abstraction (subject / resource / action /
 * resolver). This package owns the integration into the Brain's transport
 * layers: it builds {@link de.mhus.vance.shared.permission.SecurityContext}s
 * from authenticated requests / WebSocket connections, exposes a
 * {@link de.mhus.vance.brain.permission.RequestAuthority} façade that inbound
 * code calls in one line, and translates
 * {@link de.mhus.vance.shared.permission.PermissionDeniedException} into the
 * right wire response (HTTP 403 / WebSocket error frame).
 */
@NullMarked
package de.mhus.vance.brain.permission;

import org.jspecify.annotations.NullMarked;
