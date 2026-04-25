/**
 * Terminal UI layer. Two interaction surfaces:
 * <ul>
 *   <li>{@link de.mhus.vance.foot.ui.ChatRepl} — the JLine 3 REPL for chat
 *       and slash commands. The default surface.</li>
 *   <li>{@link de.mhus.vance.foot.ui.LanternaSession} — full-screen Lanterna
 *       excursions for menus and modal dialogs. Pauses JLine, runs in the
 *       alternate screen buffer, returns control on exit.</li>
 * </ul>
 * {@link de.mhus.vance.foot.ui.InterfaceService} is the mode controller;
 * {@link de.mhus.vance.foot.ui.ChatTerminal} is the verbosity-filtered output
 * sink (distinct from SLF4J — this is user-facing terminal output, not log).
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.foot.ui;
