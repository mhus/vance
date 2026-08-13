package de.mhus.vance.shared.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.session.SuspendCause;
import de.mhus.vance.api.session.SuspendPolicy;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * TOCTOU guard on {@link SessionService#suspend}: the atomic write must be
 * conditioned on the status observed at read time, so a session that became
 * active (IDLE → RUNNING) between the read and the write is not clobbered to
 * SUSPENDED (which would orphan its running engine). Mongo is mocked — the
 * test verifies the atomic op carries the status guard, not Mongo behaviour.
 */
class SessionServiceTest {

    private SessionRepository repository;
    private MongoTemplate mongoTemplate;
    private SessionService service;

    @BeforeEach
    void setUp() {
        repository = mock(SessionRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        service = new SessionService(repository, mongoTemplate);
    }

    @Test
    void suspend_guardsWriteOnObservedStatus() {
        SessionDocument s = SessionDocument.builder()
                .sessionId("s-1").status(SessionStatus.IDLE)
                .onSuspend(SuspendPolicy.KEEP).build();
        when(repository.findBySessionId("s-1")).thenReturn(Optional.of(s));
        UpdateResult res = mock(UpdateResult.class);
        when(res.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(SessionDocument.class))).thenReturn(res);

        service.suspend("s-1", SuspendCause.IDLE, 5_000);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(captor.capture(), any(Update.class),
                eq(SessionDocument.class));
        org.bson.Document q = captor.getValue().getQueryObject();
        // Guarded on both sessionId AND the observed status (IDLE) — a
        // concurrent transition away from IDLE makes the write no-op.
        assertThat(q).containsEntry("sessionId", "s-1").containsKey("status");
        assertThat(q.toString()).contains("IDLE");
    }

    @Test
    void suspend_alreadySuspended_isNoOp_noWrite() {
        SessionDocument s = SessionDocument.builder()
                .sessionId("s-1").status(SessionStatus.SUSPENDED).build();
        when(repository.findBySessionId("s-1")).thenReturn(Optional.of(s));

        service.suspend("s-1", SuspendCause.IDLE, 0);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(SessionDocument.class));
    }

    @Test
    void suspend_unknownSession_isNoOp() {
        when(repository.findBySessionId("ghost")).thenReturn(Optional.empty());

        service.suspend("ghost", SuspendCause.IDLE, 0);

        verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
                eq(SessionDocument.class));
    }

    @Test
    void setProjectId_writesProjectIdBySessionId_andReportsModified() {
        UpdateResult res = mock(UpdateResult.class);
        when(res.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(SessionDocument.class))).thenReturn(res);

        boolean ok = service.setProjectId("s-1", "projB");

        assertThat(ok).isTrue();
        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(query.capture(), update.capture(),
                eq(SessionDocument.class));
        assertThat(query.getValue().getQueryObject().toJson()).contains("s-1");
        assertThat(update.getValue().getUpdateObject().toJson()).contains("projectId").contains("projB");
    }

    @Test
    void setProjectId_unknownSession_returnsFalse() {
        UpdateResult res = mock(UpdateResult.class);
        when(res.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(SessionDocument.class))).thenReturn(res);

        assertThat(service.setProjectId("ghost", "projB")).isFalse();
    }

    @Test
    void actingUserId_serverOwnedSystemSession_hasNoUser() {
        SessionDocument s = SessionDocument.builder()
                .sessionId("s-agrajag")
                .userId(SessionService.SYSTEM_OWNER)
                .system(true)
                .build();

        // null is the framework's "no user" value — the tool path turns it
        // into SecurityContext.SYSTEM instead of an unknown user subject.
        assertThat(SessionService.actingUserId(s)).isNull();
    }

    @Test
    void actingUserId_systemSessionWithRealOwner_keepsUser() {
        SessionDocument s = SessionDocument.builder()
                .sessionId("s-scheduler")
                .userId("alice")
                .system(true)
                .build();

        assertThat(SessionService.actingUserId(s)).isEqualTo("alice");
    }

    @Test
    void actingUserId_nonSystemSession_neverElevatesOnOwnerNameAlone() {
        SessionDocument s = SessionDocument.builder()
                .sessionId("s-user")
                .userId(SessionService.SYSTEM_OWNER)
                .system(false)
                .build();

        assertThat(SessionService.actingUserId(s)).isEqualTo(SessionService.SYSTEM_OWNER);
    }
}
