/**
 * Server-side support for the user-progress side-channel:
 * {@link de.mhus.vance.brain.progress.ProgressEmitter} fills the source
 * block from the active process and forwards the typed payload through
 * {@link de.mhus.vance.brain.events.ClientEventPublisher}.
 *
 * <p>See {@code specification/user-progress-channel.md}.
 */
@NullMarked
package de.mhus.vance.brain.progress;

import org.jspecify.annotations.NullMarked;
