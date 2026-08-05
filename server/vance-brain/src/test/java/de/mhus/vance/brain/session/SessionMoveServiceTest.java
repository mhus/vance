package de.mhus.vance.brain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.sessiongroup.SessionGroupService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Behaviour tests for {@link SessionMoveService}: the guard rejections
 * (missing session, same project, absent target, running process) and the
 * happy-path orchestration order. Collaborating services are mocked — the
 * tests verify which writes are issued, not persistence.
 */
@ExtendWith(MockitoExtension.class)
class SessionMoveServiceTest {

    private static final String T = "acme";
    private static final String FROM = "p1";
    private static final String TO = "p2";
    private static final String SID = "sess_1";

    @Mock private SessionService sessionService;
    @Mock private ThinkProcessService thinkProcessService;
    @Mock private MemoryService memoryService;
    @Mock private SessionGroupService sessionGroupService;
    @Mock private ProjectService projectService;

    private SessionMoveService service;

    @BeforeEach
    void setUp() {
        service = new SessionMoveService(
                sessionService, thinkProcessService, memoryService,
                sessionGroupService, projectService);
    }

    private SessionDocument session() {
        return SessionDocument.builder()
                .sessionId(SID).tenantId(T).projectId(FROM).userId("u1").build();
    }

    @Test
    void move_retargetsProcessesAndSession_thenCleansUpSourceProject() {
        when(sessionService.findBySessionId(SID)).thenReturn(Optional.of(session()));
        when(projectService.existsByTenantAndName(T, TO)).thenReturn(true);
        when(thinkProcessService.findBySessionAndStatus(T, SID, ThinkProcessStatus.RUNNING))
                .thenReturn(List.of());
        when(thinkProcessService.retargetProject(T, SID, TO)).thenReturn(2);
        when(memoryService.deleteBySession(T, SID)).thenReturn(4L);
        when(sessionGroupService.removeSessionFromProject(T, FROM, SID)).thenReturn(1L);

        SessionMoveService.MoveResult result = service.move(T, SID, TO);

        assertThat(result.fromProjectId()).isEqualTo(FROM);
        assertThat(result.toProjectId()).isEqualTo(TO);
        assertThat(result.processesRetargeted()).isEqualTo(2);
        assertThat(result.memoriesDeleted()).isEqualTo(4L);
        assertThat(result.groupsCleared()).isEqualTo(1L);
        verify(sessionService).forceUnbind(SID);
        verify(thinkProcessService).retargetProject(T, SID, TO);
        verify(sessionService).setProjectId(SID, TO);
        verify(memoryService).deleteBySession(T, SID);
        verify(sessionGroupService).removeSessionFromProject(T, FROM, SID);
    }

    @Test
    void move_runningProcess_throwsBusy_andWritesNothing() {
        when(sessionService.findBySessionId(SID)).thenReturn(Optional.of(session()));
        when(projectService.existsByTenantAndName(T, TO)).thenReturn(true);
        when(thinkProcessService.findBySessionAndStatus(T, SID, ThinkProcessStatus.RUNNING))
                .thenReturn(List.of(ThinkProcessDocument.builder().id("p").sessionId(SID)
                        .status(ThinkProcessStatus.RUNNING).build()));

        assertThatThrownBy(() -> service.move(T, SID, TO))
                .isInstanceOf(SessionMoveService.SessionBusyException.class);
        verify(thinkProcessService, never()).retargetProject(T, SID, TO);
        verify(sessionService, never()).setProjectId(SID, TO);
        verify(memoryService, never()).deleteBySession(T, SID);
    }

    @Test
    void move_sameProject_throwsSameProject_beforeCheckingTarget() {
        when(sessionService.findBySessionId(SID)).thenReturn(Optional.of(session()));

        assertThatThrownBy(() -> service.move(T, SID, FROM))
                .isInstanceOf(SessionMoveService.SameProjectException.class);
        verify(projectService, never()).existsByTenantAndName(T, FROM);
    }

    @Test
    void move_targetProjectMissing_throwsTargetNotFound() {
        when(sessionService.findBySessionId(SID)).thenReturn(Optional.of(session()));
        when(projectService.existsByTenantAndName(T, TO)).thenReturn(false);

        assertThatThrownBy(() -> service.move(T, SID, TO))
                .isInstanceOf(SessionMoveService.TargetProjectNotFoundException.class);
        verify(thinkProcessService, never()).retargetProject(T, SID, TO);
    }

    @Test
    void move_sessionMissing_throwsSessionNotFound() {
        when(sessionService.findBySessionId(SID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.move(T, SID, TO))
                .isInstanceOf(SessionMoveService.SessionNotFoundException.class);
    }
}
