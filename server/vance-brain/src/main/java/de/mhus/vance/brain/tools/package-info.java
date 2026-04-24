/**
 * Runtime tool model.
 *
 * <p>{@link de.mhus.vance.brain.tools.Tool} is the server-side
 * counterpart to {@link de.mhus.vance.api.tools.ToolSpec}: it carries the
 * same metadata plus an {@code invoke} method. {@link
 * de.mhus.vance.brain.tools.ToolSource} sources tools from somewhere
 * (server-local Spring beans, connected clients, plugins); {@link
 * de.mhus.vance.brain.tools.ToolDispatcher} aggregates over all sources
 * for a given scope and dispatches invocations.
 *
 * <p>Think-engines access the per-call subset through {@link
 * de.mhus.vance.brain.tools.ContextToolsApi}, handed out by {@code
 * ThinkEngineContext.tools()}.
 */
@NullMarked
package de.mhus.vance.brain.tools;

import org.jspecify.annotations.NullMarked;
