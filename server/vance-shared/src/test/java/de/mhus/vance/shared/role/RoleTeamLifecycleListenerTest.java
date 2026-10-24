package de.mhus.vance.shared.role;

import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.team.TeamDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleTeamLifecycleListenerTest {

    @Mock
    RoleBootstrap bootstrap;

    @InjectMocks
    RoleTeamLifecycleListener listener;

    @Test
    void onTeamCreated_clearsWhatAnEarlierTeamOfThatNameLeft() {
        listener.onTeamCreated(
                TeamDocument.builder().tenantId("acme").name("rd").build());

        verify(bootstrap).revokeAllOfTeam("acme", "rd");
    }

    @Test
    void onTeamDeleted_revokesEverything() {
        listener.onTeamDeleted("acme", "rd");

        verify(bootstrap).revokeAllOfTeam("acme", "rd");
    }
}
