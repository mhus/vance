/**
 * YAML parser and validator for {@link de.mhus.vance.api.action.TriggerAction}.
 * Used by every trigger document loader — scheduler, event,
 * workflow-task — so the disjunction
 * ({@code recipe:} | {@code script:} | {@code workflow:}) is enforced
 * in one place.
 *
 * <p>See {@code planning/trigger-actions.md} §5.2.
 */
@NullMarked
package de.mhus.vance.shared.action;

import org.jspecify.annotations.NullMarked;
