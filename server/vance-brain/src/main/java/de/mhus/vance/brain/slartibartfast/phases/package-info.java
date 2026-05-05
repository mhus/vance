/**
 * Per-phase implementations of the Slartibartfast lifecycle. Each
 * phase is a Spring {@code @Component} that the engine dispatches
 * to from its {@code runTurn} switch. Phases own their LLM-call
 * mechanics (prompt building, schema validation, re-prompt loop)
 * and mutate the shared {@link de.mhus.vance.api.slartibartfast.ArchitectState}
 * passed in as an argument.
 */
@NullMarked
package de.mhus.vance.brain.slartibartfast.phases;

import org.jspecify.annotations.NullMarked;
