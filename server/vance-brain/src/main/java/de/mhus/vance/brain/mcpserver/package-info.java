/**
 * MCP <em>server</em> surface — exposes Vance's own tool set to external
 * MCP clients (Claude Code, Cursor, …) over Streamable HTTP.
 *
 * <p>This is the mirror image of {@code vance-toolpack}'s MCP <em>client</em>
 * code: instead of Vance calling out to a foreign MCP server, a foreign
 * agent calls into Vance's {@link de.mhus.vance.brain.servertool.ServerToolService}
 * tool catalogue and dispatches through
 * {@link de.mhus.vance.brain.tools.ToolDispatcher} (which enforces
 * permissions per invocation).
 *
 * <p>Transport is JSON-RPC 2.0 over a single {@code POST /brain/{tenant}/mcp}.
 * Auth reuses the existing {@code Authorization: Bearer} JWT chain — no new
 * mechanism. See {@code specification/public/} once the surface graduates
 * past the test-system stage.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.mcpserver;
