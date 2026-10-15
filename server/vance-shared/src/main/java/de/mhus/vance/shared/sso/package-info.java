/**
 * Data side of the SSO login ({@code planning/sso-login.md}): pending sign-in
 * requests between the redirect to the identity provider and its callback.
 *
 * <p>The account binding itself lives on the user
 * ({@link de.mhus.vance.shared.user.ExternalIdentity}), owned by
 * {@link de.mhus.vance.shared.user.UserService}.
 */
@org.jspecify.annotations.NullMarked
package de.mhus.vance.shared.sso;
