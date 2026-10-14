/**
 * The OAuth authorization server ({@code planning/oauth-server.md},
 * {@code specification/public/oauth-server.md}): Vance issues tokens for its
 * own MCP doors ({@code /mcp}, {@code /mcp/{project}}, {@code /agents}) and,
 * for first-party clients, a full-account login.
 *
 * <p>Runs parallel to the existing login ({@code POST /access/{user}}),
 * cookies and integration tokens — nothing here replaces them. The OAuth
 * <em>client</em> (Vance logging into Google, Slack, …) lives in
 * {@code de.mhus.vance.brain.oauth} under {@code /oauth-client}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.oauthserver;
