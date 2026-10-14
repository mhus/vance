package de.mhus.vance.shared.oauthserver;

import de.mhus.vance.shared.user.UserPasswordChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A password change ends every OAuth grant of the account. Changing the
 * password is what a user does when they suspect someone else has access;
 * a grant handed out under the old password — a connected app, a foot login
 * on a lost laptop — would otherwise keep working for up to a year.
 *
 * <p>Listens to an event instead of being called by {@code UserService}, so
 * the user service does not depend on the OAuth server (which itself asks the
 * user service whether an account may still act).
 */
@Component
@RequiredArgsConstructor
public class OAuthServerPasswordChangeListener {

    /** Revoke reason stored on the grant and written to the audit record. */
    public static final String REASON = "password-changed";

    private final OAuthServerGrantService grantService;

    @EventListener
    public void onPasswordChanged(UserPasswordChangedEvent event) {
        grantService.revokeAllForUser(event.tenantId(), event.name(), REASON);
    }
}
