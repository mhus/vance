/**
 * Project actions the admin shell performs against the cluster — placement,
 * hand-off, and the placement selector.
 *
 * <p>Separate from {@code de.mhus.vance.anus.shell} because these are
 * operations, not a command surface: they answer with facts a caller can act
 * on, and the {@code @Command} methods turn those into the text an operator
 * reads. Anything that only exists to be typed at a terminal — the option
 * grammar, the CSV convention, the typed confirmation, the formatting — stays
 * on the shell side.
 *
 * <p>Everything here still lives in anus, deliberately. These paths reach
 * {@code /internal/**} with the cluster-wide technical token and carry no
 * tenant authorization; that is the admin shell's standing and nobody else's.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.anus.project;
