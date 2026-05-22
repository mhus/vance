package de.mhus.vance.foot.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.agent.ClientAgentDocService;
import de.mhus.vance.foot.command.SuggestionCache;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.tools.ClientToolService;
import de.mhus.vance.foot.tools.exec.FootExecEventDispatcher;
import de.mhus.vance.foot.ui.BusyIndicator;
import de.mhus.vance.foot.ui.StatusBar;
import de.mhus.vance.foot.ui.WindowTitleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Tests for the foot-side {@link SessionService} bookkeeping: bind/clear
 * mutate atomic refs, side-effects on optional collaborators fire only
 * when the bean is available, suggestion cache is invalidated on every
 * topology shift.
 */
class SessionServiceTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StatusBar> statusBar = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ClientToolService> clientToolService = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ClientAgentDocService> clientAgentDocService = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<SuggestionCache> suggestionCache = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<FootExecEventDispatcher> execDispatcher = mock(ObjectProvider.class);

    private StatusBar bar;
    private ClientToolService tools;
    private ClientAgentDocService agentDoc;
    private SuggestionCache cache;
    private FootExecEventDispatcher execDispatcherBean;
    private WindowTitleService windowTitle;
    private SessionService session;

    @BeforeEach
    void setUp() {
        bar = mock(StatusBar.class);
        tools = mock(ClientToolService.class);
        agentDoc = mock(ClientAgentDocService.class);
        cache = mock(SuggestionCache.class);
        execDispatcherBean = mock(FootExecEventDispatcher.class);
        // Tests run without a controlling TTY, so the public constructor's
        // System.console() gate makes the service a no-op for our purposes.
        windowTitle = new WindowTitleService(new FootConfig(), new BusyIndicator());
        when(statusBar.getIfAvailable()).thenReturn(bar);
        when(clientToolService.getIfAvailable()).thenReturn(tools);
        when(clientAgentDocService.getIfAvailable()).thenReturn(agentDoc);
        when(suggestionCache.getIfAvailable()).thenReturn(cache);
        when(execDispatcher.getIfAvailable()).thenReturn(execDispatcherBean);

        @SuppressWarnings("unchecked")
        ObjectProvider<BusyIndicator> busyIndicatorProvider = mock(ObjectProvider.class);
        when(busyIndicatorProvider.getIfAvailable()).thenReturn(null);
        session = new SessionService(statusBar, clientToolService, clientAgentDocService,
                suggestionCache, execDispatcher, windowTitle, busyIndicatorProvider);
    }

    @Test
    void initially_currentAndActiveProcess_areNull() {
        assertThat(session.current()).isNull();
        assertThat(session.activeProcess()).isNull();
    }

    @Test
    void bind_storesSession_andTriggersAllCollaborators() {
        session.bind("sess-1", "proj");

        SessionService.BoundSession current = session.current();
        assertThat(current).isNotNull();
        assertThat(current.sessionId()).isEqualTo("sess-1");
        assertThat(current.projectId()).isEqualTo("proj");

        verify(bar, atLeastOnce()).refresh();
        verify(tools).registerAll();
        verify(agentDoc).uploadIfPresent();
        verify(cache).invalidateAll();
    }

    @Test
    void clear_dropsBindAndActiveProcess() {
        session.bind("sess-1", "proj");
        session.setActiveProcess("p-1");

        session.clear();

        assertThat(session.current()).isNull();
        assertThat(session.activeProcess()).isNull();
        // status bar refreshes on bind, on setActiveProcess and on clear
        verify(bar, atLeast(3)).refresh();
        verify(cache, atLeast(2)).invalidateAll();
    }

    @Test
    void setActiveProcess_isPureClientStateChange_doesNotInvalidateCache() {
        session.setActiveProcess("p-1");

        assertThat(session.activeProcess()).isEqualTo("p-1");
        verify(bar, atLeastOnce()).refresh();
        // active-process is purely REPL-local; no client-tool re-register,
        // no doc upload, no cache invalidation.
        org.mockito.Mockito.verify(tools, org.mockito.Mockito.never()).registerAll();
        org.mockito.Mockito.verify(cache, org.mockito.Mockito.never()).invalidateAll();
    }

    @Test
    void setActiveProcess_acceptsNull_toClearTarget() {
        session.setActiveProcess("p-1");
        session.setActiveProcess(null);

        assertThat(session.activeProcess()).isNull();
    }

    @Test
    void bind_withNoCollaborators_doesNotThrow() {
        // Optional collaborators all absent — bind must still succeed.
        when(statusBar.getIfAvailable()).thenReturn(null);
        when(clientToolService.getIfAvailable()).thenReturn(null);
        when(clientAgentDocService.getIfAvailable()).thenReturn(null);
        when(suggestionCache.getIfAvailable()).thenReturn(null);

        session.bind("sess-1", "proj");

        assertThat(session.current()).isNotNull();
    }
}
