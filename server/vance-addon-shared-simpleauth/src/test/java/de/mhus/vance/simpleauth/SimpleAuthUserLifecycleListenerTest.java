package de.mhus.vance.simpleauth;

import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.user.UserDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Both ends of the account lifecycle clear the same name-keyed state — the
 * delete because the grant must not outlive its subject, the create because a
 * grant that already outlived one must not be inherited by the next holder of
 * the name.
 */
@ExtendWith(MockitoExtension.class)
class SimpleAuthUserLifecycleListenerTest {

    @Mock
    SimpleAuthPermissionBootstrap bootstrap;

    @InjectMocks
    SimpleAuthUserLifecycleListener listener;

    @Test
    void onUserDeleted_revokesEveryGrantHeldUnderTheName() {
        listener.onUserDeleted("acme", "_trillian-void-a7f3");

        verify(bootstrap).revokeAll("acme", "_trillian-void-a7f3");
    }

    @Test
    void onUserCreated_clearsGrantsLeftBehindByAnEarlierHolderOfTheName() {
        listener.onUserCreated(UserDocument.builder()
                .tenantId("acme").name("_trillian-void-a7f3").build());

        verify(bootstrap).revokeAll("acme", "_trillian-void-a7f3");
    }
}
