package de.mhus.vance.brain.tools.mcp.oauth;

/**
 * Discovery, registration or login preparation for an MCP server failed.
 * The message is meant for the person configuring the server — it names
 * what is missing ("server offers no client registration — set
 * auth.clientId"), never secret material.
 */
public class McpOAuthException extends RuntimeException {

    public McpOAuthException(String message) {
        super(message);
    }

    public McpOAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
