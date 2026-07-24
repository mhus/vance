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
}
