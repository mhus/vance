package de.mhus.vance.shared.enginemessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The engine-message queue's at-least-once/at-most-once contract lives in
 * {@link EngineMessageService}: idempotent {@code acceptDelivery} (insert /
 * set-deliveredAt-once / no-op-if-delivered / re-read on duplicate-key race)
 * and the {@code markDrained} drainedAt-IsNull guard. Pin both.
 */
class EngineMessageServiceTest {

    private final EngineMessageRepository repository = mock(EngineMessageRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final EngineMessageService service =
            new EngineMessageService(repository, mongoTemplate);

    private static EngineMessageDocument msg(String id, Instant deliveredAt) {
        return EngineMessageDocument.builder()
                .messageId(id).tenantId("acme")
                .senderProcessId("s").targetProcessId("t")
                .deliveredAt(deliveredAt).build();
    }

    @Test
    void acceptDelivery_insertsWhenAbsent_withDeliveredAtSet() {
        when(repository.findByMessageId("m1")).thenReturn(Optional.empty());
        when(repository.insert(any(EngineMessageDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EngineMessageDocument out = service.acceptDelivery(msg("m1", null));

        assertThat(out.getDeliveredAt()).isNotNull();
        verify(repository).insert(any(EngineMessageDocument.class));
    }

    @Test
    void acceptDelivery_setsDeliveredAtOnce_whenPresentAndNull() {
        when(repository.findByMessageId("m2")).thenReturn(Optional.of(msg("m2", null)));
        UpdateResult r = mock(UpdateResult.class);
        when(r.getModifiedCount()).thenReturn(1L);
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class),
                any(Class.class))).thenReturn(r);

        EngineMessageDocument out = service.acceptDelivery(msg("m2", null));

        assertThat(out.getDeliveredAt()).isNotNull();
        verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class), any(Class.class));
        verify(repository, never()).insert(any(EngineMessageDocument.class));
    }

    @Test
    void acceptDelivery_noOp_whenAlreadyDelivered() {
        Instant already = Instant.parse("2026-01-01T00:00:00Z");
        when(repository.findByMessageId("m3")).thenReturn(Optional.of(msg("m3", already)));

        EngineMessageDocument out = service.acceptDelivery(msg("m3", null));

        assertThat(out.getDeliveredAt()).isEqualTo(already);
        verify(mongoTemplate, never()).updateFirst(any(), any(), any(Class.class));
        verify(repository, never()).insert(any(EngineMessageDocument.class));
    }

    @Test
    void acceptDelivery_reReadsOnDuplicateKeyRace() {
        EngineMessageDocument raced = msg("m4", Instant.now());
        when(repository.findByMessageId("m4"))
                .thenReturn(Optional.empty())     // pre-insert
                .thenReturn(Optional.of(raced));  // post-race re-read
        when(repository.insert(any(EngineMessageDocument.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        EngineMessageDocument out = service.acceptDelivery(msg("m4", null));

        assertThat(out).isSameAs(raced);
        verify(repository, times(2)).findByMessageId("m4");
    }

    @Test
    void markDrained_empty_doesNotQuery() {
        service.markDrained(List.of());
        verify(mongoTemplate, never()).updateMulti(any(), any(), any(Class.class));
    }

    @Test
    void markDrained_guardsOnDrainedAtIsNull() {
        UpdateResult r = mock(UpdateResult.class);
        when(r.getModifiedCount()).thenReturn(2L);
        when(mongoTemplate.updateMulti(any(Query.class), any(Update.class),
                any(Class.class))).thenReturn(r);

        service.markDrained(List.of("a", "b"));

        ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateMulti(q.capture(), any(Update.class), any(Class.class));
        // The IsNull guard keeps an already-drained message from being re-drained.
        assertThat(q.getValue().getQueryObject().toJson()).contains("drainedAt");
    }
}
