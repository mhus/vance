package de.mhus.vance.shared.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Verifies that {@link SessionService#unbindStaleConnections} builds the
 * right {@code updateMulti} call without loading any documents.
 *
 * <p>The actual Mongo behaviour (partial index, server-side update) is
 * exercised by the QA suite — here we only pin the query shape so the
 * filter doesn't drift silently.
 */
class SessionServiceUnbindStaleTest {

    @Test
    void unbindStaleConnections_filtersOnBoundAndOlderThanCutoff() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        UpdateResult result = mock(UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(7L);
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(SessionDocument.class)))
                .thenReturn(result);

        SessionService service = new SessionService(mock(SessionRepository.class), mongo);
        Instant cutoff = Instant.parse("2026-06-12T06:00:00Z");

        long n = service.unbindStaleConnections(cutoff);

        assertThat(n).isEqualTo(7L);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        org.mockito.Mockito.verify(mongo)
                .updateMulti(queryCaptor.capture(), updateCaptor.capture(),
                        eq(SessionDocument.class));

        Document queryDoc = queryCaptor.getValue().getQueryObject();
        assertThat(queryDoc).containsKeys("boundConnectionId", "lastActivityAt");
        // boundConnectionId: { $ne: null }
        Document boundCriteria = queryDoc.get("boundConnectionId", Document.class);
        assertThat(boundCriteria.get("$ne")).isNull();
        assertThat(boundCriteria.containsKey("$ne")).isTrue();
        // lastActivityAt: { $lt: cutoff }
        Document activityCriteria = queryDoc.get("lastActivityAt", Document.class);
        assertThat(activityCriteria.get("$lt")).isEqualTo(cutoff);

        Document updateDoc = updateCaptor.getValue().getUpdateObject();
        Document set = updateDoc.get("$set", Document.class);
        assertThat(set).containsEntry("boundConnectionId", null);
        // Sweep must not touch lastActivityAt — we want the original
        // timestamp preserved for diagnostics.
        assertThat(set.containsKey("lastActivityAt")).isFalse();
    }

    @Test
    void unbindStaleConnections_returnsZeroWhenNothingMatches() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        UpdateResult result = mock(UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(0L);
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(SessionDocument.class)))
                .thenReturn(result);

        SessionService service = new SessionService(mock(SessionRepository.class), mongo);

        long n = service.unbindStaleConnections(Instant.now());

        assertThat(n).isZero();
    }
}
