/**
 * Picocli top-level commands. The root {@code vance-foot} command and its
 * subcommands ({@code chat}, {@code connect}, …) are entered <strong>before</strong>
 * the JLine REPL — they are how the user picks what mode the binary starts in.
 *
 * <p>Slash commands inside the REPL live in {@link de.mhus.vance.foot.command}
 * — a separate dispatcher with a separate registry, but they may parse their
 * arguments via Picocli too if the command is non-trivial.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.foot.cli;
