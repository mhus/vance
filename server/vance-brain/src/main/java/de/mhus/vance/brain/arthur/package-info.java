/**
 * Arthur — the reactive session-chat think-engine and the reference
 * implementation of the orchestrator pattern. Talks to the user,
 * delegates deep work to worker engines via the {@code process_*}
 * tools, and synthesizes worker {@code ProcessEvent}s back into the
 * chat. See {@code specification/arthur-engine.md}.
 */
@NullMarked
package de.mhus.vance.brain.arthur;

import org.jspecify.annotations.NullMarked;
