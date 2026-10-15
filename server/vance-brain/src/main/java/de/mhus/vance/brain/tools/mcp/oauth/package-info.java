/**
 * OAuth login for remote MCP servers configured as server tools
 * ({@code type: mcp_server}, {@code auth.type: oauth}): discovery of the
 * server's authorization server (RFC 9728 / RFC 8414), client registration
 * (Client ID Metadata Document or RFC 7591), the brain-managed provider
 * document of type {@code mcp}, and the login endpoints. Tokens and the
 * code flow itself stay in {@code de.mhus.vance.brain.oauth}.
 */
@NullMarked
package de.mhus.vance.brain.tools.mcp.oauth;

import org.jspecify.annotations.NullMarked;
