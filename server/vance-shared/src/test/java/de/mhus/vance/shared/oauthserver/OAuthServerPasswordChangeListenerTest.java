package de.mhus.vance.shared.oauthserver;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.user.UserPasswordChangedEvent;
import org.junit.jupiter.api.Test;

class OAuthServerPasswordChangeListenerTest {

    private final OAuthServerGrantService grants = mock(OAuthServerGrantService.class);
    private final OAuthServerPasswordChangeListener listener = new OAuthServerPasswordChangeListener(grants);

    @Test
    void passwordChange_revokesEveryGrantOfTheAccount() {
        listener.onPasswordChanged(new UserPasswordChangedEvent("acme", "alice"));

        verify(grants).revokeAllForUser("acme", "alice", "password-changed");
    }
}
