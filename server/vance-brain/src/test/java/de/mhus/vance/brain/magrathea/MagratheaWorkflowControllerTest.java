package de.mhus.vance.brain.magrathea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.magrathea.MagratheaStartDocumentRequest;
import de.mhus.vance.api.magrathea.MagratheaStartRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.magrathea.MagratheaDebugSetup;
import de.mhus.vance.shared.magrathea.MagratheaJournalService;
import de.mhus.vance.shared.magrathea.MagratheaRunBinding;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.magrathea.MagratheaWorkflowLoader;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * The run's execution identity is the authenticated caller. A start request
 * carries no {@code startedBy}: accepting one would let a project member run a
 * plan as a tenant admin.
 */
class MagratheaWorkflowControllerTest {

    private MagratheaWorkflowService service;
    private MagratheaWorkflowController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        service = mock(MagratheaWorkflowService.class);
        controller = new MagratheaWorkflowController(
                service,
                mock(MagratheaWorkflowLoader.class),
                mock(MagratheaStateProjector.class),
                mock(MagratheaJournalService.class),
                mock(RequestAuthority.class));
        request = mock(HttpServletRequest.class);
    }

    @Test
    void start_runs_as_the_authenticated_user() throws Exception {
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn("alice");
        when(service.start(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("run-1");

        controller.startWorkflow("acme", "proj", "wf", new MagratheaStartRequest(), request);

        verify(service)
                .start(
                        eq("acme"),
                        eq("proj"),
                        eq("wf"),
                        any(),
                        eq("alice"),
                        eq(null),
                        eq(null),
                        any(MagratheaRunBinding.class),
                        any(MagratheaDebugSetup.class));
    }

    @Test
    void start_without_an_authenticated_user_is_refused_instead_of_running_headless() {
        assertThatThrownBy(() -> controller.startWorkflow("acme", "proj", "wf", new MagratheaStartRequest(), request))
                .isInstanceOf(ResponseStatusException.class);
        verify(service, never()).start(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void start_document_without_an_authenticated_user_is_refused() {
        MagratheaStartDocumentRequest body = new MagratheaStartDocumentRequest();
        body.setPath("documents/x.yaml");

        assertThatThrownBy(() -> controller.startWorkflowDocument("acme", "proj", body, request))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(body.getPath()).isEqualTo("documents/x.yaml");
    }
}
