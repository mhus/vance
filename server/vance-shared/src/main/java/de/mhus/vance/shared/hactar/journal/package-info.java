/**
 * Typed journal record subclasses for {@code hactar_journal}. Pattern
 * borrowed from Nimbus' {@code de.mhus.nimbus.world.shared.workflow}:
 * a generic container row carries one of the typed record bodies
 * serialised via Jackson.
 *
 * <p>System records (Start, Status, TaskStarted, TaskResult, …) are
 * defined here as concrete classes — used by Hactar internals.
 * User-defined workflow variables (from {@code storeAs:}) ride on the
 * single generic {@link VarRecord} with a {@code key} and a free-form
 * {@code value}; see plan §3.2.1 for the rationale (no per-YAML
 * codegen).
 *
 * <p>See {@code planning/workflow-service.md} §3.2 for the full table.
 */
@NullMarked
package de.mhus.vance.shared.hactar.journal;

import org.jspecify.annotations.NullMarked;
