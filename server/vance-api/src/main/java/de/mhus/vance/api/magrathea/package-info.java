/**
 * Wire-contract DTOs and enums for the Magrathea workflow subsystem —
 * project-scoped event-driven automation defined as YAML documents
 * under {@code _vance/workflows/<name>.yaml}.
 *
 * <p>See {@code planning/workflow-service.md} for the full design;
 * {@code specification/workflows.md} will be authored from the plan in
 * etappe W15.
 *
 * <p>User-facing term is "workflow"; internal Java types carry the
 * {@code Magrathea} prefix (Adams-universe naming — see the plan's naming
 * convention section). REST endpoints live under
 * {@code /brain/{tenant}/project/{project}/workflows}; agent tools live
 * under the {@code workflow} label.
 */
@NullMarked
package de.mhus.vance.api.magrathea;

import org.jspecify.annotations.NullMarked;
