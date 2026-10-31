package de.mhus.vance.brain.access;

import de.mhus.vance.shared.user.EmailTokenMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which sign-in ways a request may use.
 *
 * <ul>
 *   <li><b>E-mail code</b> — per tenant: the installation offers it
 *       ({@code vance.auth.email-token.enabled}, default off) <em>and</em> the
 *       tenant chose it (setting {@value EmailTokenMode#SETTING_KEY} on
 *       {@code _tenant}, default off), see {@link EmailTokenMode}. The password
 *       can still be set, and still serves WebDAV, but no longer signs in at
 *       {@code POST …/access/{username}} of such a tenant.</li>
 *   <li><b>SSO</b> — per installation ({@code vance.auth.sso.enabled}, default
 *       off): the {@code /brain/_sso/} endpoints. The SSO login resolves the tenant
 *       only after the identity provider answered, so it cannot be a tenant
 *       choice. An installation that signs in through SSO has to set it.</li>
 * </ul>
 */
@Component
public class LoginModes {

    private final EmailTokenMode emailTokenMode;
    private final boolean sso;

    public LoginModes(EmailTokenMode emailTokenMode, @Value("${vance.auth.sso.enabled:false}") boolean sso) {
        this.emailTokenMode = emailTokenMode;
        this.sso = sso;
    }

    /** The accounts of {@code tenant} sign in with the e-mail code. */
    public boolean emailTokenEnabled(String tenant) {
        return emailTokenMode.enabledFor(tenant);
    }

    public boolean ssoEnabled() {
        return sso;
    }

    /** The password signs in unless the e-mail code replaces it for this tenant. */
    public boolean passwordLoginEnabled(String tenant) {
        return !emailTokenMode.enabledFor(tenant);
    }
}
