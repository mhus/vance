package de.mhus.vance.brain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.session.SessionMetadataPatchRequest;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * The insights share button's mutation half: the flip is admin-gated (not
 * owner-gated), goes through {@code SessionService.patchMetadata}, and a
 * CLOSED or system session is refused instead of silently mis-shared.
 */
@ExtendWith(MockitoExtension.class)
class SessionSharingAdminControllerTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess-1";
    private static final String PROJECT = "proj-1";

    @Mock
    private SessionService sessionService;

    @Mock
    private RequestAuthority authority;

    @Mock
    private HttpServletRequest request;

    private SessionSharingAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionSharingAdminController(sessionService, authority);
    }

    @Test
    void sharedTrueFlipsTheFlag() {
        givenSession(SessionStatus.RUNNING, false);

        ResponseEntity<Void> response =
                controller.setSharing(TENANT, SESSION, new SessionSharingAdminController.SharingRequest(true), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ArgumentCaptor<SessionMetadataPatchRequest> patch = ArgumentCaptor.forClass(SessionMetadataPatchRequest.class);
        verify(sessionService).patchMetadata(eq(SESSION), patch.capture());
        assertThat(patch.getValue().getAllowMultipleClients()).isTrue();
    }

    @Test
    void sharedFalseFlipsItBack() {
        givenSession(SessionStatus.RUNNING, true);

        controller.setSharing(TENANT, SESSION, new SessionSharingAdminController.SharingRequest(false), request);

        ArgumentCaptor<SessionMetadataPatchRequest> patch = ArgumentCaptor.forClass(SessionMetadataPatchRequest.class);
        verify(sessionService).patchMetadata(eq(SESSION), patch.capture());
        assertThat(patch.getValue().getAllowMultipleClients()).isFalse();
    }

    @Test
    void missingSharedIsRejected() {
        assertThatThrownBy(() -> controller.setSharing(
                        TENANT, SESSION, new SessionSharingAdminController.SharingRequest(null), request))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(sessionService, never()).patchMetadata(any(), any());
    }

    @Test
    void closedSessionIsRefused() {
        givenSession(SessionStatus.CLOSED, true);

        assertThatThrownBy(() -> controller.setSharing(
                        TENANT, SESSION, new SessionSharingAdminController.SharingRequest(true), request))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(sessionService, never()).patchMetadata(any(), any());
    }

    @Test
    void systemSessionStaysPrivate() {
        when(sessionService.findBySessionId(SESSION))
                .thenReturn(Optional.of(SessionDocument.builder()
                        .sessionId(SESSION)
                        .tenantId(TENANT)
                        .projectId(PROJECT)
                        .userId("_user_hub")
                        .system(true)
                        .status(SessionStatus.RUNNING)
                        .build()));

        assertThatThrownBy(() -> controller.setSharing(
                        TENANT, SESSION, new SessionSharingAdminController.SharingRequest(true), request))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        verify(sessionService, never()).patchMetadata(any(), any());
    }

    @Test
    void unknownSessionIsNotFound() {
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.setSharing(
                        TENANT, SESSION, new SessionSharingAdminController.SharingRequest(true), request))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void adminGateIsEnforcedOnTheSession() {
        givenSession(SessionStatus.RUNNING, false);

        controller.setSharing(TENANT, SESSION, new SessionSharingAdminController.SharingRequest(true), request);

        verify(authority).enforce(eq(request), eq(new Resource.Session(TENANT, PROJECT, SESSION)), eq(Action.ADMIN));
    }

    private void givenSession(SessionStatus status, boolean shared) {
        when(sessionService.findBySessionId(SESSION))
                .thenReturn(Optional.of(SessionDocument.builder()
                        .sessionId(SESSION)
                        .tenantId(TENANT)
                        .projectId(PROJECT)
                        .userId("owner")
                        .allowMultipleClients(shared)
                        .status(status)
                        .build()));
    }
}
