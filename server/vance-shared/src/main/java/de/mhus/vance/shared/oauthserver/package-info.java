/**
 * Data side of the OAuth authorization server ({@code planning/oauth-server.md}):
 * dynamically registered clients, grants (the consent records behind every
 * OAuth access and refresh token), pending authorization requests and
 * authorization codes.
 *
 * <p>Not to be confused with {@code de.mhus.vance.shared.oauth}, which holds
 * the state of the OAuth <em>client</em> (Vance logging into Google, Slack,
 * Atlassian). Here Vance is the server other programs log into.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.shared.oauthserver;
