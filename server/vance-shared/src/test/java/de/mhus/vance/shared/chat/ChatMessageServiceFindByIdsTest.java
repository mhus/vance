package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.session.SessionService;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * {@link ChatMessageService#findByIds} contract: empty input is a
 * no-op, the issued query pins tenant + process (no cross-scope
 * leakage), and order is chronological.
 */
class ChatMessageServiceFindByIdsTest {

    private ChatMessageRepository repository;
    private MongoTemplate mongoTemplate;
    private SessionService sessionService;
    private ApplicationEventPublisher eventPublisher;
    private ChatMessageService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        mongoTemplate = mock(MongoTemplate.class);
        sessionService = mock(SessionService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        when(mongoTemplate.find(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(List.of());
        service = new ChatMessageService(repository, mongoTemplate, sessionService, eventPublisher);
    }

    @Test
    void emptyIds_returnsEmpty_andSkipsMongoCall() {
        List<ChatMessageDocument> out = service.findByIds("t", "p", List.of());

        assertThat(out).isEmpty();
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void issuesQueryWithTenantProcessAndIdsIn() {
        service.findByIds("t", "p", Set.of("m-1", "m-2"));

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(cap.capture(), eq(ChatMessageDocument.class));
        Document criteria = cap.getValue().getQueryObject();

        assertThat(criteria.get("tenantId")).isEqualTo("t");
        // The single-process overload delegates to the multi-process
        // variant; the wire query is always $in for consistency.
        Document procClause = criteria.get("thinkProcessId", Document.class);
        @SuppressWarnings("unchecked")
        Iterable<Object> procs = (Iterable<Object>) procClause.get("$in");
        assertThat(procs).containsExactly("p");
        Document idClause = criteria.get("_id", Document.class);
        @SuppressWarnings("unchecked")
        Iterable<Object> ids = (Iterable<Object>) idClause.get("$in");
        assertThat(ids).containsExactlyInAnyOrder("m-1", "m-2");
    }

    @Test
    void multiProcessOverload_widensProcessInClause() {
        service.findByIds("t", Set.of("p", "child-1"), Set.of("m-1"));

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(cap.capture(), eq(ChatMessageDocument.class));
        Document criteria = cap.getValue().getQueryObject();

        Document procClause = criteria.get("thinkProcessId", Document.class);
        @SuppressWarnings("unchecked")
        Iterable<Object> procs = (Iterable<Object>) procClause.get("$in");
        assertThat(procs).containsExactlyInAnyOrder("p", "child-1");
    }

    @Test
    void multiProcessOverload_emptyProcessSet_returnsEmpty() {
        List<ChatMessageDocument> out =
                service.findByIds("t", Set.of(), Set.of("m-1"));

        assertThat(out).isEmpty();
        verify(mongoTemplate, org.mockito.Mockito.never())
                .find(any(Query.class), eq(ChatMessageDocument.class));
    }

    @Test
    void sortsByCreatedAtAscending() {
        service.findByIds("t", "p", Set.of("m-1"));

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(cap.capture(), eq(ChatMessageDocument.class));
        assertThat(cap.getValue().getSortObject().get("createdAt")).isEqualTo(1);
    }
}
