/**
 * Slash command framework. Each {@link de.mhus.vance.foot.command.SlashCommand}
 * is a Spring bean; {@link de.mhus.vance.foot.command.CommandService} dispatches
 * incoming {@code /name args...} lines from the REPL to the matching bean.
 *
 * <p>Slash commands are <strong>user-initiated</strong> — distinct from
 * Brain-initiated tool calls handled in {@code de.mhus.vance.foot.tool}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.foot.command;
