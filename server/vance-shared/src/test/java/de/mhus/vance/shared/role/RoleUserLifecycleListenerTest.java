package de.mhus.vance.shared.role;

import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.user.UserDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleUserLifecycleListenerTest {

    @Mock
    RoleBootstrap bootstrap;

    @InjectMocks
    RoleUserLifecycleListener listener;

    @Test
    void onUserCreated_clearsWhateverAnEarlierAccountOfThatNameLeft() {
        listener.onUserCreated(
                UserDocument.builder().tenantId("acme").name("_trillian-03725").build());

        verify(bootstrap).revokeAll("acme", "_trillian-03725");
    }

    @Test
    void onUserDeleted_revokesEverything() {
        listener.onUserDeleted("acme", "alice");

        verify(bootstrap).revokeAll("acme", "alice");
    }
}
