package de.mhus.vance.shared.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Verifies the multi-user bind-holder escalation atomic update — see
 * {@code planning/multi-user-sessions.md} §3b. The query must scope
 * to the leaving editor so a concurrent disconnect (or a session
 * that was already unbound) does not yank the bind from a survivor
 * who already took over.
 */
class SessionServiceEscalateBindTest {

    @Test
    void tryEscalateBind_filtersOnLeavingEditor_andStampsSuccessor() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        UpdateResult result = mock(UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(1L);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(SessionDocument.class)))
                .thenReturn(result);

        SessionService service = new SessionService(mock(SessionRepository.class), mongo);

        boolean escalated = service.tryEscalateBind("sess-1", "ed-leaving", "ed-survivor");

        assertThat(escalated).isTrue();

        ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> u = ArgumentCaptor.forClass(Update.class);
        org.mockito.Mockito.verify(mongo).updateFirst(
                q.capture(), u.capture(), eq(SessionDocument.class));
        Document filter = q.getValue().getQueryObject();
        assertThat(filter.getString("sessionId")).isEqualTo("sess-1");
        assertThat(filter.getString("boundConnectionId")).isEqualTo("ed-leaving");
        Document set = (Document) u.getValue().getUpdateObject().get("$set");
        assertThat(set.getString("boundConnectionId")).isEqualTo("ed-survivor");
        assertThat(set.get("lastActivityAt")).isNotNull();
    }

    @Test
    void tryEscalateBind_noMatchingRow_returnsFalse() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        UpdateResult result = mock(UpdateResult.class);
        when(result.getModifiedCount()).thenReturn(0L);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(SessionDocument.class)))
                .thenReturn(result);

        SessionService service = new SessionService(mock(SessionRepository.class), mongo);

        boolean escalated = service.tryEscalateBind("sess-1", "ed-leaving", "ed-survivor");

        assertThat(escalated).isFalse();
    }
}
