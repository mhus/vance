/**
 * SSO login ({@code planning/sso-login.md}): signing in to Vance with an
 * external OpenID Connect account — v1 Google.
 *
 * <p>The installation-wide config {@code _vance/config/sso.yaml} decides which
 * e-mail domain belongs to which tenant (Workspace accounts, checked via
 * {@code hd}) and which tenants accept individually prepared private Gmail
 * accounts. The identity is bound to {@code (issuer, sub)} on the user
 * ({@link de.mhus.vance.shared.user.ExternalIdentity}); after the callback Vance
 * issues the same credentials as the password login.
 *
 * <p>Not the OAuth <em>client</em> for connectors ({@code brain/oauth}) — that
 * one fetches API tokens and does not validate ID tokens. Here validating the
 * ID token is the whole point.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.brain.sso;
