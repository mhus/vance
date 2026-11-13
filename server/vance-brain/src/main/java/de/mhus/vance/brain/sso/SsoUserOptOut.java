package de.mhus.vance.brain.sso;

import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The operator's per-user "not through SSO": the protected setting {@value #KEY}
 * = {@value #DENIED} on the user's hub project ({@code _user_<login>}). Such a
 * user signs in with password or e-mail code only — the login page does not
 * offer them Google, and a Google sign-in for the account is refused.
 *
 * <p>The key lives in the protected {@code vancetope.*} namespace: the user
 * cannot lift it themselves, only the admin shell sets it ({@code force} family).
 * Read with {@code forceGetStringValue}, as the regular read path hides the
 * namespace.
 */
@Component
public class SsoUserOptOut {

    /** Setting key on the user's hub project. */
    public static final String KEY = "vancetope.sso.login";

    /** Value that keeps the user off SSO. */
    public static final String DENIED = "denied";

    private final SettingService settings;

    public SsoUserOptOut(SettingService settings) {
        this.settings = settings;
    }

    /** Whether {@code username} of {@code tenant} must not sign in through SSO. */
    public boolean deniedFor(String tenant, @Nullable String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        String value = settings.forceGetStringValue(
                tenant, SettingService.SCOPE_PROJECT, HomeBootstrapService.hubProjectName(username.trim()), KEY);
        return DENIED.equalsIgnoreCase(value == null ? "" : value.trim());
    }
}
