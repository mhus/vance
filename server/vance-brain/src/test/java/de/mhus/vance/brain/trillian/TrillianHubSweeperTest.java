package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.brain.session.SessionLifecycleService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.project.maintenance.ProjectDataHandler;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The brain's narrow sweep of a Trillian's hub (D1): only Trillian hubs,
 * live sessions stopped first, every handler run — and the project
 * document, the index back to the data, kept when one of them failed.
 */
class TrillianHubSweeperTest {

    private static final String HUB = "_user__trillian-void-1234";

    private final ProjectDataHandler documents = handler("documents", 100);
    private final ProjectDataHandler sessions = handler("sessions", 200);
    private final SessionLifecycleService lifecycle = mock(SessionLifecycleService.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private TrillianHubSweeper sweeper;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<ProjectDataHandler> handlers = mock(ObjectProvider.class);
        when(handlers.orderedStream()).thenAnswer(inv -> Stream.of(sessions, documents));
        ObjectProvider<SessionLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
        when(lifecycleProvider.getObject()).thenReturn(lifecycle);
        when(projectService.existsByTenantAndName("acme", HUB)).thenReturn(true);
        sweeper = new TrillianHubSweeper(handlers, lifecycleProvider, sessionService, projectService);
    }

    @Test
    void refusesAnythingThatIsNotATrillianHub() {
        assertThatThrownBy(() -> sweeper.sweepUserHub("acme", "_user_mara"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sweeper.sweepUserHub("acme", "_vance")).isInstanceOf(IllegalArgumentException.class);
        verify(projectService, never()).deleteUserHub(anyString(), anyString());
    }

    @Test
    void sweepsEveryHandlerInOrder_thenDropsTheProject() {
        assertThat(sweeper.sweepUserHub("acme", HUB)).isTrue();

        InOrder order = Mockito.inOrder(documents, sessions, projectService);
        order.verify(documents).delete("acme", HUB);
        order.verify(sessions).delete("acme", HUB);
        order.verify(projectService).deleteUserHub("acme", HUB);
    }

    @Test
    void aFailingHandler_keepsTheProjectDocumentForARerun() {
        doThrow(new IllegalStateException("mongo down")).when(documents).delete("acme", HUB);

        assertThat(sweeper.sweepUserHub("acme", HUB)).isFalse();

        // The others still ran — and the index back to what is left stays.
        verify(sessions).delete("acme", HUB);
        verify(projectService, never()).deleteUserHub(anyString(), anyString());
    }

    @Test
    void liveSessionsAreClosedBeforeTheirRowsGo() {
        SessionDocument live = new SessionDocument();
        live.setSessionId("s-loop");
        live.setStatus(SessionStatus.IDLE);
        SessionDocument closed = new SessionDocument();
        closed.setSessionId("s-old");
        closed.setStatus(SessionStatus.CLOSED);
        when(sessionService.listForProject("acme", HUB)).thenReturn(List.of(live, closed));

        sweeper.sweepUserHub("acme", HUB);

        InOrder order = Mockito.inOrder(lifecycle, documents);
        order.verify(lifecycle).closeWithCascade("s-loop");
        order.verify(documents).delete("acme", HUB);
        verify(lifecycle, never()).closeWithCascade("s-old");
    }

    @Test
    void aHubThatIsAlreadyGone_isASuccess() {
        when(projectService.existsByTenantAndName("acme", HUB)).thenReturn(false);

        assertThat(sweeper.sweepUserHub("acme", HUB)).isTrue();
        verify(documents, never()).delete(anyString(), anyString());
    }

    private static ProjectDataHandler handler(String id, int order) {
        ProjectDataHandler h = mock(ProjectDataHandler.class);
        when(h.id()).thenReturn(id);
        when(h.order()).thenReturn(order);
        return h;
    }
}
