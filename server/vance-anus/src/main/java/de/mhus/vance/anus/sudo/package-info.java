/**
 * One-shot {@code --sudo} execution mode for Anus.
 *
 * <p>{@link de.mhus.vance.anus.sudo.SudoBootstrap} parses argv before Spring
 * Boot starts. {@link de.mhus.vance.anus.sudo.SudoShellRunner} takes over
 * the Spring Shell boot sequence when sudo commands were found: arms the
 * access window, runs each command in order, aborts on the first non-zero
 * exit, and drops the window again before the process terminates.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus.sudo;
