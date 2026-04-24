/**
 * Built-in server tools. Split into two groups:
 *
 * <ul>
 *   <li>Meta-tools ({@code find_tools}, {@code describe_tool},
 *       {@code invoke_tool}) — primary, always advertised, let the LLM
 *       discover and call secondary tools.
 *   <li>Domain tools — things the assistant should actually be able to
 *       do. Only the uncontroversial ones are primary; specialised ones
 *       stay secondary to keep the prompt slim.
 * </ul>
 */
@NullMarked
package de.mhus.vance.brain.tools.builtins;

import org.jspecify.annotations.NullMarked;
