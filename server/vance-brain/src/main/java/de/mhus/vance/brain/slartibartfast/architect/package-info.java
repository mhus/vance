/**
 * Strategy beans encapsulating schema-specific knowledge inside
 * the Slartibartfast engine. The engine itself (lifecycle,
 * recovery, audit, inbox dialog, EXECUTING/EXECUTION_VALIDATING)
 * stays schema-agnostic; each {@link de.mhus.vance.brain.slartibartfast.architect.SchemaArchitect}
 * carries the system prompt, sub-recipe-listing rule, expected
 * engine name and validator branch for one {@link
 * de.mhus.vance.api.slartibartfast.OutputSchemaType}.
 *
 * <p>See {@code planning/slart-schema-architects-refactor.md}
 * for the refactor rationale.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.slartibartfast.architect;
