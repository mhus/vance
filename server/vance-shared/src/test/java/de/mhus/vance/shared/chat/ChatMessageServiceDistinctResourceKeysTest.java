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
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Service-level behaviour for the marker timestamp lookup and the
 * distinct-resource aggregation that powers {@code list_edited_resources}.
 * Mongo is mocked — we verify the query shape (pipeline stages, criteria
 * keys) and the projection of {@code _id} → typed resource key.
 */
class ChatMessageServiceDistinctResourceKeysTest {

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

    // ─── findLatestCreatedAtForTag ──────────────────────────────────────

    @Test
    void findLatestCreatedAtForTag_emptyProcessSet_returnsEmpty() {
        assertThat(service.findLatestCreatedAtForTag("t", Set.of(), "X")).isEmpty();
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void findLatestCreatedAtForTag_blankTag_returnsEmpty() {
        assertThat(service.findLatestCreatedAtForTag("t", Set.of("p"), "")).isEmpty();
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void findLatestCreatedAtForTag_noHit_returnsEmpty() {
        when(mongoTemplate.findOne(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(null);

        assertThat(service.findLatestCreatedAtForTag("t", Set.of("p"), "FILE_EDIT"))
                .isEmpty();
    }

    @Test
    void findLatestCreatedAtForTag_returnsCreatedAtOfMatch_andSortsDescending() {
        Instant when = Instant.parse("2026-05-11T14:23:01Z");
        ChatMessageDocument hit = ChatMessageDocument.builder()
                .id("m-99").content("x").build();
        hit.setCreatedAt(when);
        when(mongoTemplate.findOne(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(hit);

        Instant got = service.findLatestCreatedAtForTag(
                "t", Set.of("p"), "PLAN_STEP_STARTED:cleanup").orElseThrow();

        assertThat(got).isEqualTo(when);

        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findOne(cap.capture(), eq(ChatMessageDocument.class));
        Query q = cap.getValue();
        assertThat(q.getSortObject().get("createdAt")).isEqualTo(-1);
        assertThat(q.getLimit()).isEqualTo(1);
        Document criteria = q.getQueryObject();
        assertThat(criteria.get("tags")).isEqualTo("PLAN_STEP_STARTED:cleanup");
    }

    // ─── distinctResourceKeys ───────────────────────────────────────────

    @Test
    void distinctResourceKeys_emptyProcessSet_returnsEmpty() {
        assertThat(service.distinctResourceKeys("t", Set.of(), null)).isEmpty();
        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void distinctResourceKeys_projectsAggregatedIdsAndStripsPrefix() {
        Document a = new Document("_id", "RESOURCE:CLIENT_FILE:/abs/Foo.java");
        Document b = new Document("_id", "RESOURCE:DOCUMENT:65f-doc");
        @SuppressWarnings("unchecked")
        AggregationResults<Document> aggResults = mock(AggregationResults.class);
        when(aggResults.getMappedResults()).thenReturn(List.of(a, b));
        when(mongoTemplate.aggregate(any(Aggregation.class),
                eq(ChatMessageDocument.class), eq(Document.class)))
                .thenReturn(aggResults);

        List<String> keys = service.distinctResourceKeys("t", Set.of("p"), null);

        // RESOURCE: prefix is stripped — the LLM sees the typed key only.
        assertThat(keys).containsExactly(
                "CLIENT_FILE:/abs/Foo.java",
                "DOCUMENT:65f-doc");
    }

    @Test
    void distinctResourceKeys_aggregationPipeline_includesMatchUnwindGroupSort() {
        @SuppressWarnings("unchecked")
        AggregationResults<Document> aggResults = mock(AggregationResults.class);
        when(aggResults.getMappedResults()).thenReturn(List.of());
        when(mongoTemplate.aggregate(any(Aggregation.class),
                eq(ChatMessageDocument.class), eq(Document.class)))
                .thenReturn(aggResults);

        service.distinctResourceKeys("t", Set.of("p"),
                Instant.parse("2026-05-11T14:00:00Z"));

        ArgumentCaptor<Aggregation> cap = ArgumentCaptor.forClass(Aggregation.class);
        verify(mongoTemplate).aggregate(cap.capture(),
                eq(ChatMessageDocument.class), eq(Document.class));
        String pipeline = cap.getValue().toString();
        // Stage shape is what the contract relies on — order matters.
        assertThat(pipeline).contains("$match", "$unwind", "$group", "$sort");
        // Tenant + process pin + tag prefix + since floor must all be
        // somewhere in the pipeline.
        assertThat(pipeline).contains("tenantId", "thinkProcessId", "RESOURCE:", "createdAt");
    }
}
