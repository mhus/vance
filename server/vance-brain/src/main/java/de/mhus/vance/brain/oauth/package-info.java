/**
 * OAuth integration for tool authentication.
 *
 * <p>OAuth-Provider-Beans expand a per-tenant
 * {@link de.mhus.vance.brain.oauth.OAuthProviderConfig} into the
 * Authorization-Code-Flow (initiate, exchange, refresh). Provider
 * instances are configuration (one YAML per tenant per provider under
 * the {@code oauth/<providerId>.yaml} document path); provider
 * <i>types</i> are code (OIDC, generic OAuth 2.0, plus quirk-subclasses
 * for Slack / Atlassian / Google). See
 * {@code planning/tool-oauth.md}.
 */
@NullMarked
package de.mhus.vance.brain.oauth;

import org.jspecify.annotations.NullMarked;
