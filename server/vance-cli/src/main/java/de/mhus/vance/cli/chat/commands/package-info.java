/**
 * Slash-command infrastructure and built-in commands for the chat TUI.
 *
 * <p>All commands — local ({@code /help}, {@code /quit}, {@code /connect},
 * {@code /disconnect}, {@code /clear}) and remote ({@code /ping}, future
 * session/project commands) — go through the same {@link
 * de.mhus.vance.cli.chat.commands.CommandRegistry}. Each command is a class
 * implementing {@link de.mhus.vance.cli.chat.commands.Command}; whether it
 * performs a local action or builds a WebSocket envelope is the command's
 * own concern.
 */
@NullMarked
package de.mhus.vance.cli.chat.commands;

import org.jspecify.annotations.NullMarked;
