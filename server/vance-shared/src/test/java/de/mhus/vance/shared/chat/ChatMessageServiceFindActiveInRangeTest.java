package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.session.SessionService;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Verifies the slice loader used by topic-recompaction:
 * {@code findActiveInRange} must pin to one process, filter out
 * already-archived rows, honour the inclusive {@code createdAt} bounds,
 * and order chronologically. Mongo is mocked — we assert on the query
 * shape, not the result content.
 */
class ChatMessageServiceFindActiveInRangeTest {

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
        service = new ChatMessageService(repository, mongoTemplate, sessionService, eventPublisher);
    }

    @Test
    void blankProcessId_returnsEmpty_withoutHittingMongo() {
        assertThat(service.findActiveInRange("t", "", Instant.now(), Instant.now())).isEmpty();
        assertThat(service.findActiveInRange("t", null, null, null)).isEmpty();
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void bothBoundsSet_buildsBetweenQuery_pinningTenantProcessAndUnarchived() {
        when(mongoTemplate.find(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(List.of());
        Instant from = Instant.parse("2026-05-11T14:00:00Z");
        Instant to = Instant.parse("2026-05-11T15:00:00Z");

        service.findActiveInRange("tenantA", "proc-1", from, to);

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(cap.capture(), eq(ChatMessageDocument.class));
        Query q = cap.getValue();
        Document criteria = q.getQueryObject();

        assertThat(criteria.get("tenantId")).isEqualTo("tenantA");
        assertThat(criteria.get("thinkProcessId")).isEqualTo("proc-1");
        // archivedInMemoryId IS NULL pin
        assertThat(criteria.get("archivedInMemoryId")).isNull();
        assertThat(criteria.containsKey("archivedInMemoryId")).isTrue();

        Document createdAt = (Document) criteria.get("createdAt");
        assertThat(createdAt.get("$gte")).isEqualTo(from);
        assertThat(createdAt.get("$lte")).isEqualTo(to);

        // ascending by createdAt for chronological replay
        assertThat(q.getSortObject().get("createdAt")).isEqualTo(1);
    }

    @Test
    void onlyFromBound_omitsLte() {
        when(mongoTemplate.find(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(List.of());
        Instant from = Instant.parse("2026-05-11T14:00:00Z");

        service.findActiveInRange("t", "proc-1", from, null);

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(cap.capture(), eq(ChatMessageDocument.class));
        Document createdAt = (Document) cap.getValue().getQueryObject().get("createdAt");
        assertThat(createdAt.get("$gte")).isEqualTo(from);
        assertThat(createdAt.containsKey("$lte")).isFalse();
    }

    @Test
    void noBounds_omitsCreatedAtCriterion_butStillPinsArchivedNull() {
        when(mongoTemplate.find(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(List.of());

        service.findActiveInRange("t", "proc-1", null, null);

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(cap.capture(), eq(ChatMessageDocument.class));
        Document criteria = cap.getValue().getQueryObject();
        assertThat(criteria.containsKey("createdAt")).isFalse();
        assertThat(criteria.containsKey("archivedInMemoryId")).isTrue();
    }
}
