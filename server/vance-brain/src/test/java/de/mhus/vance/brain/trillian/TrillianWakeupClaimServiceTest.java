package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The cross-pod referee of the Trillian loop: one insert decides a wake
 * slot, and a lease keeps a turn or a bootstrap on one pod at a time — with
 * a dead holder overtaken after its TTL instead of after the hour-long
 * reaper.
 */
class TrillianWakeupClaimServiceTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final TrillianWakeupClaimService service = new TrillianWakeupClaimService(mongoTemplate);

    @Test
    void claim_firstInsertWins_withSlotScopedId() {
        assertThat(service.claim("acme", "loop-1", "w1000")).isTrue();

        ArgumentCaptor<TrillianWakeupClaimDocument> doc = ArgumentCaptor.forClass(TrillianWakeupClaimDocument.class);
        verify(mongoTemplate).insert(doc.capture());
        assertThat(doc.getValue().getId()).isEqualTo("acme/loop-1/w1000");
    }

    @Test
    void claim_aSlotAnotherPodHolds_isLost() {
        when(mongoTemplate.insert(any(TrillianWakeupClaimDocument.class))).thenThrow(new DuplicateKeyException("x"));

        assertThat(service.claim("acme", "loop-1", "w1000")).isFalse();
    }

    @Test
    void acquireLease_freeLease_isTakenWithHolderToken() {
        String token = service.acquireLease("turn/acme/loop-1", Duration.ofMinutes(5));

        assertThat(token).isNotBlank();
        verify(mongoTemplate, never())
                .updateFirst(any(Query.class), any(Update.class), eq(TrillianWakeupClaimDocument.class));
        // The token is what the claim document stores — releaseLease matches it.
        ArgumentCaptor<TrillianWakeupClaimDocument> doc = ArgumentCaptor.forClass(TrillianWakeupClaimDocument.class);
        verify(mongoTemplate).insert(doc.capture());
        assertThat(doc.getValue().getHolder()).isEqualTo(token);
    }

    @Test
    void acquireLease_heldAndFresh_isRefused() {
        when(mongoTemplate.insert(any(TrillianWakeupClaimDocument.class))).thenThrow(new DuplicateKeyException("x"));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TrillianWakeupClaimDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThat(service.acquireLease("turn/acme/loop-1", Duration.ofMinutes(5)))
                .isNull();
    }

    @Test
    void acquireLease_heldButStale_isTakenOver() {
        // The holder died mid-turn; waiting for the TTL reaper would freeze
        // the loop for an hour.
        when(mongoTemplate.insert(any(TrillianWakeupClaimDocument.class))).thenThrow(new DuplicateKeyException("x"));
        when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(TrillianWakeupClaimDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertThat(service.acquireLease("turn/acme/loop-1", Duration.ofMinutes(5)))
                .isNotBlank();
    }

    @Test
    void releaseLease_byItsHolder_removesTheLeaseDocument() {
        when(mongoTemplate.remove(any(Query.class), eq(TrillianWakeupClaimDocument.class)))
                .thenReturn(DeleteResult.acknowledged(1));
        service.releaseLease("turn/acme/loop-1", "token-1");

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).remove(query.capture(), eq(TrillianWakeupClaimDocument.class));
        assertThat(query.getValue().getQueryObject().get("_id")).isEqualTo("lease/turn/acme/loop-1");
        // Owner-checked: the removal query carries the holder token, so only
        // the acquirer's release can take the lease down.
        assertThat(query.getValue().getQueryObject().get("holder")).isEqualTo("token-1");
    }

    @Test
    void releaseLease_afterATakeover_isIgnored() {
        // Review-20 finding: a holder that outlives its TTL (GC pause) wakes
        // up after another pod took the stale lease — its release must not
        // free the successor's lease document.
        when(mongoTemplate.remove(any(Query.class), eq(TrillianWakeupClaimDocument.class)))
                .thenReturn(DeleteResult.acknowledged(0));

        service.releaseLease("turn/acme/loop-1", "stale-token");

        // A no-op: the remove ran, matched nothing, and the successor's
        // lease document survives.
        verify(mongoTemplate).remove(any(Query.class), eq(TrillianWakeupClaimDocument.class));
    }
}
