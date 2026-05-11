package de.mhus.vance.shared.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Atomic-update contracts for the volatile context-assembly state on
 * {@link ThinkProcessService}. Mongo is mocked — what we verify is the
 * shape of the issued {@code Update}: that {@code readState} pushes
 * use {@code $push.$slice} for LRU trimming, that {@code shownOnce} adds
 * use {@code $addToSet}, and that {@code clearVolatileContextState}
 * resets both fields atomically.
 */
class ThinkProcessServiceVolatileStateTest {

    private ThinkProcessRepository repository;
    private MongoTemplate mongoTemplate;
    private ApplicationEventPublisher eventPublisher;
    private EngineMessageService engineMessageService;
    private ThinkProcessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ThinkProcessRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        engineMessageService = mock(EngineMessageService.class);
        service = new ThinkProcessService(
                repository, mongoTemplate, eventPublisher, engineMessageService);
    }

    // ─── appendReadStateEntry ───────────────────────────────────────────

    @Test
    void appendReadStateEntry_blankProcessId_isNoOp() {
        service.appendReadStateEntry("  ",
                new ReadStateEntry("CLIENT_FILE:/x", "h", Instant.now(), false, null),
                100);

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void appendReadStateEntry_nullEntry_isNoOp() {
        service.appendReadStateEntry("p-1", null, 100);

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void appendReadStateEntry_issuesPushWithNegativeSlice() {
        ReadStateEntry e = new ReadStateEntry(
                "CLIENT_FILE:/abs/Foo.java", "h1", Instant.now(), false, 1024L);

        service.appendReadStateEntry("p-1", e, 50);

        ArgumentCaptor<Update> cap = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), cap.capture(),
                eq(ThinkProcessDocument.class));
        // $slice with a NEGATIVE value keeps the most recent N — that's
        // what gives us LRU eviction at the head of the array.
        String dump = cap.getValue().toString();
        assertThat(dump).contains("$push", "readState", "$slice", "-50", "$each");
    }

    @Test
    void appendReadStateEntry_clampsMaxEntriesToAtLeastOne() {
        // A zero or negative bound is nonsense — the implementation
        // raises it to 1 so we never push without a slice that keeps
        // at least one entry.
        ReadStateEntry e = new ReadStateEntry(
                "CLIENT_FILE:/x", "h", Instant.now(), false, null);

        service.appendReadStateEntry("p-1", e, 0);

        ArgumentCaptor<Update> cap = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), cap.capture(),
                eq(ThinkProcessDocument.class));
        assertThat(cap.getValue().toString()).contains("-1");
    }

    // ─── tryAddShownOnce ────────────────────────────────────────────────

    @Test
    void tryAddShownOnce_blankInputs_returnFalseWithoutMongoCall() {
        assertThat(service.tryAddShownOnce("", "X")).isFalse();
        assertThat(service.tryAddShownOnce("p-1", "  ")).isFalse();
        assertThat(service.tryAddShownOnce(null, "X")).isFalse();
        assertThat(service.tryAddShownOnce("p-1", null)).isFalse();

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void tryAddShownOnce_firstAdd_returnsTrue() {
        UpdateResult ok = mock(UpdateResult.class);
        when(ok.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class))).thenReturn(ok);

        boolean added = service.tryAddShownOnce("p-1", "CLAUDE.md");

        assertThat(added).isTrue();
    }

    @Test
    void tryAddShownOnce_alreadyPresent_returnsFalse() {
        UpdateResult noop = mock(UpdateResult.class);
        when(noop.getModifiedCount()).thenReturn(0L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class))).thenReturn(noop);

        boolean added = service.tryAddShownOnce("p-1", "CLAUDE.md");

        assertThat(added).isFalse();
    }

    @Test
    void tryAddShownOnce_issuesAddToSetUpdate() {
        UpdateResult ok = mock(UpdateResult.class);
        when(ok.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                eq(ThinkProcessDocument.class))).thenReturn(ok);

        service.tryAddShownOnce("p-1", "kit-welcome");

        ArgumentCaptor<Update> cap = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), cap.capture(),
                eq(ThinkProcessDocument.class));
        String dump = cap.getValue().toString();
        assertThat(dump).contains("$addToSet", "shownOnce", "kit-welcome");
    }

    // ─── clearVolatileContextState ──────────────────────────────────────

    @Test
    void clearVolatileContextState_blankId_isNoOp() {
        service.clearVolatileContextState("  ");
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void clearVolatileContextState_resetsBothFieldsAtomically() {
        service.clearVolatileContextState("p-1");

        ArgumentCaptor<Update> cap = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), cap.capture(),
                eq(ThinkProcessDocument.class));
        String dump = cap.getValue().toString();
        // Single $set with both fields — single round-trip, atomic.
        assertThat(dump).contains("$set", "readState", "shownOnce");
    }
}
