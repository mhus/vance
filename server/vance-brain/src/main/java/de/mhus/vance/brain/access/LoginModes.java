package de.mhus.vance.brain.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which sign-in ways this installation offers — two independent switches of the
 * instance, not of a tenant: the SSO login resolves the tenant only after the
 * identity provider answered.
 *
 * <ul>
 *   <li>{@code vance.auth.email-token.enabled} (default off): sign in with a
 *       code sent by e-mail. The password can still be set, and still serves
 *       WebDAV, but no longer signs in at {@code POST …/access/{username}}.</li>
 *   <li>{@code vance.auth.sso.enabled} (default on, which is how SSO behaved
 *       before the switch existed): the {@code /brain/_sso/} endpoints.</li>
 * </ul>
 */
@Component
public class LoginModes {

    private final boolean emailToken;
    private final boolean sso;

    public LoginModes(
            @Value("${vance.auth.email-token.enabled:false}") boolean emailToken,
            @Value("${vance.auth.sso.enabled:true}") boolean sso) {
        this.emailToken = emailToken;
        this.sso = sso;
    }

    public boolean emailTokenEnabled() {
        return emailToken;
    }

    public boolean ssoEnabled() {
        return sso;
    }

    /** The password signs in unless the e-mail code replaces it. */
    public boolean passwordLoginEnabled() {
        return !emailToken;
    }
}
