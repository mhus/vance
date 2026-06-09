package de.mhus.vance.brain.fook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.fook.FookSubmissionRequestDto;
import de.mhus.vance.api.fook.FookSubmissionResponseDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-logic tests for {@link FookController}. The
 * downstream {@link FookService} and {@link RequestAuthority} are
 * mocked. Single-text input model.
 */
class FookControllerTest {

    private static final String TENANT = "acme";
    private static final String USER = "alice";

    private FookService fookService;
    private RequestAuthority authority;
    private HttpServletRequest request;
    private FookController controller;

    @BeforeEach
    void setUp() {
        fookService = mock(FookService.class);
        authority = mock(RequestAuthority.class);
        request = mock(HttpServletRequest.class);
        when(authority.contextOf(request))
                .thenReturn(SecurityContext.user(USER, TENANT, List.of()));
        when(fookService.submit(any())).thenReturn("sub-123");
        controller = new FookController(fookService, authority);
    }

    @Test
    void submit_enforces_write_on_tenant_and_returns_submission_id() {
        FookSubmissionRequestDto body = FookSubmissionRequestDto.builder()
                .text("Brain crashes on boot when recipes.yaml is missing.")
                .build();

        FookSubmissionResponseDto resp = controller.submit(TENANT, body, request);

        verify(authority).enforce(request,
                new Resource.Tenant(TENANT), Action.WRITE);
        assertThat(resp.getSubmissionId()).isEqualTo("sub-123");
        assertThat(resp.getStatus()).isEqualTo("queued");

        ArgumentCaptor<SubmissionRequest> cap =
                ArgumentCaptor.forClass(SubmissionRequest.class);
        verify(fookService).submit(cap.capture());
        SubmissionRequest req = cap.getValue();
        assertThat(req.getText())
                .isEqualTo("Brain crashes on boot when recipes.yaml is missing.");
        assertThat(req.getReporter().getKind())
                .isEqualTo(TicketReporter.Kind.USER_DIRECT);
        assertThat(req.getReporter().getUserId()).isEqualTo(USER);
        assertThat(req.getReporter().getTenantId()).isEqualTo(TENANT);
        assertThat(req.getContext()).isNull();
    }

    @Test
    void submit_passes_projectId_and_sessionId_through_to_context() {
        FookSubmissionRequestDto body = FookSubmissionRequestDto.builder()
                .text("Add a dark-mode toggle on the settings page.")
                .projectId("web-redesign")
                .sessionId("sess-42")
                .build();

        controller.submit(TENANT, body, request);

        ArgumentCaptor<SubmissionRequest> cap =
                ArgumentCaptor.forClass(SubmissionRequest.class);
        verify(fookService).submit(cap.capture());
        TicketContext ctx = cap.getValue().getContext();
        assertThat(ctx).isNotNull();
        assertThat(ctx.getProjectId()).isEqualTo("web-redesign");
        assertThat(ctx.getSessionId()).isEqualTo("sess-42");
        assertThat(ctx.getProcessId()).isNull();
        assertThat(ctx.getRecipe()).isNull();
        assertThat(ctx.getEngine()).isNull();
    }

    @Test
    void submit_rejects_blank_text_with_400() {
        FookSubmissionRequestDto body = FookSubmissionRequestDto.builder()
                .text("   ")
                .build();

        assertThatThrownBy(() -> controller.submit(TENANT, body, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("text");
    }

    @Test
    void submit_propagates_authority_denial() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(authority).enforce(eq(request), any(), any());

        assertThatThrownBy(() -> controller.submit(TENANT,
                FookSubmissionRequestDto.builder().text("x").build(),
                request))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(fookService);
    }
}
