/**
 * Client-side tool surface. Mirrors the brain-side
 * {@code de.mhus.vance.brain.tools.*} infrastructure but inverted: a
 * {@link de.mhus.vance.foot.tools.ClientTool} is a local capability
 * the brain can call via WebSocket. The
 * {@link de.mhus.vance.foot.tools.ClientToolService} announces every
 * registered tool to the brain on session-bind and dispatches incoming
 * {@code client-tool-invoke} envelopes to the matching local
 * implementation.
 *
 * <p>By convention all client-tool names are prefixed {@code client_*}
 * so they don't shadow brain-side server tools (the brain's
 * {@code ToolDispatcher} resolves server tools first anyway, but the
 * prefix also makes it obvious to the LLM that the action runs on the
 * user's machine, not in the project workspace).
 */
@NullMarked
package de.mhus.vance.foot.tools;

import org.jspecify.annotations.NullMarked;
