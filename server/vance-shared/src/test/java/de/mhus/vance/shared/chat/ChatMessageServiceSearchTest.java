package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.session.SessionService;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextQuery;

/**
 * Search semantics on {@link ChatMessageService}. Mongo is mocked — we
 * verify the criteria document, sort, and limit reaching the driver,
 * plus the {@link TextQuery} switch when a text clause is present.
 *
 * <p>Tenant + process pinning is the single most important invariant
 * here: the LLM-facing {@code history_search} tool relies on it for
 * isolation. Every test checks both criteria appear.
 */
class ChatMessageServiceSearchTest {

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
    void searchQuery_blankTenantId_isRejected() {
        assertThatThrownBy(() ->
                new ChatMessageSearchQuery("", "p", Set.of(), null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void searchQuery_blankProcessId_isRejected() {
        assertThatThrownBy(() ->
                new ChatMessageSearchQuery("t", "", Set.of(), null, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thinkProcessId");
    }

    @Test
    void searchQuery_clampsLimitBelowOneToOne() {
        ChatMessageSearchQuery q = new ChatMessageSearchQuery("t", "p", Set.of(), null, null, 0);
        assertThat(q.limit()).isEqualTo(1);
    }

    @Test
    void searchQuery_clampsLimitAboveMaxToMax() {
        ChatMessageSearchQuery q = new ChatMessageSearchQuery("t", "p", Set.of(), null, null, 9999);
        assertThat(q.limit()).isEqualTo(ChatMessageSearchQuery.MAX_LIMIT);
    }

    @Test
    void search_minimalQuery_pinsTenantAndProcess_andOrdersByCreatedDesc() {
        service.search(ChatMessageSearchQuery.of("t", "p"));

        Query issued = captureQuery();
        Document criteria = issued.getQueryObject();
        assertThat(criteria.get("tenantId")).isEqualTo("t");
        // The single-arg search delegates to the multi-process variant
        // with allowedProcessIds={p}, so the wire query uses $in even
        // for the trivial single-process case.
        Document procClause = criteria.get("thinkProcessId", Document.class);
        @SuppressWarnings("unchecked")
        Iterable<Object> procIds = (Iterable<Object>) procClause.get("$in");
        assertThat(procIds).containsExactly("p");
        assertThat(issued.getSortObject().get("createdAt")).isEqualTo(-1);
        assertThat(issued.getLimit()).isEqualTo(ChatMessageSearchQuery.DEFAULT_LIMIT);
        assertThat(issued).isNotInstanceOf(TextQuery.class);
    }

    @Test
    void search_withTags_addsAllCriteriaForAndSemantics() {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("FILE_EDIT");
        tags.add("ERROR");
        ChatMessageSearchQuery q = new ChatMessageSearchQuery(
                "t", "p", tags, null, null, 10);

        service.search(q);

        Query issued = captureQuery();
        Document criteria = issued.getQueryObject();
        Document tagsClause = criteria.get("tags", Document.class);
        assertThat(tagsClause).containsKey("$all");
        // AND semantics: $all requires every listed tag to be present.
        // The Mongo driver accepts either a List or a Set for $all; cast
        // through Iterable so either flavour passes.
        @SuppressWarnings("unchecked")
        Iterable<Object> allValues = (Iterable<Object>) tagsClause.get("$all");
        assertThat(allValues).containsExactlyInAnyOrder("FILE_EDIT", "ERROR");
    }

    @Test
    void search_withSince_addsGteOnCreatedAt() {
        Instant since = Instant.parse("2026-05-01T00:00:00Z");
        ChatMessageSearchQuery q = new ChatMessageSearchQuery(
                "t", "p", Set.of(), null, since, 10);

        service.search(q);

        Document criteria = captureQuery().getQueryObject();
        Document createdAt = criteria.get("createdAt", Document.class);
        assertThat(createdAt.get("$gte")).isEqualTo(since);
    }

    @Test
    void search_withText_switchesToTextQuery_andKeepsTenantPin() {
        ChatMessageSearchQuery q = new ChatMessageSearchQuery(
                "t", "p", Set.of(), "provider caching", null, 10);

        service.search(q);

        Query issued = captureQuery();
        assertThat(issued).isInstanceOf(TextQuery.class);
        Document criteria = issued.getQueryObject();
        // Tenant + process pinning must survive the TextQuery switch.
        assertThat(criteria.get("tenantId")).isEqualTo("t");
        Document procClause = criteria.get("thinkProcessId", Document.class);
        @SuppressWarnings("unchecked")
        Iterable<Object> procIds = (Iterable<Object>) procClause.get("$in");
        assertThat(procIds).containsExactly("p");
        // The Mongo text-search operator is encoded as $text.$search.
        Document textOp = criteria.get("$text", Document.class);
        assertThat(textOp.get("$search")).isEqualTo("provider caching");
    }

    @Test
    void search_withMultipleProcessIds_widensInClause() {
        service.search(ChatMessageSearchQuery.of("t", "p"),
                Set.of("p", "child-1", "child-2"));

        Query issued = captureQuery();
        Document criteria = issued.getQueryObject();
        assertThat(criteria.get("tenantId")).isEqualTo("t");
        Document procClause = criteria.get("thinkProcessId", Document.class);
        @SuppressWarnings("unchecked")
        Iterable<Object> procs = (Iterable<Object>) procClause.get("$in");
        assertThat(procs).containsExactlyInAnyOrder("p", "child-1", "child-2");
    }

    @Test
    void search_emptyAllowedProcessIds_returnsEmpty_andSkipsMongoFind() {
        List<de.mhus.vance.shared.chat.ChatMessageDocument> out =
                service.search(ChatMessageSearchQuery.of("t", "p"), Set.of());

        assertThat(out).isEmpty();
        org.mockito.Mockito.verify(mongoTemplate, org.mockito.Mockito.never())
                .find(any(Query.class), eq(ChatMessageDocument.class));
    }

    @Test
    void search_propagatesClampedLimit_intoMongoQuery() {
        ChatMessageSearchQuery q = new ChatMessageSearchQuery(
                "t", "p", Set.of(), null, null, 9999);

        service.search(q);

        assertThat(captureQuery().getLimit()).isEqualTo(ChatMessageSearchQuery.MAX_LIMIT);
    }

    private Query captureQuery() {
        ArgumentCaptor<Query> cap = ArgumentCaptor.forClass(Query.class);
        org.mockito.Mockito.verify(mongoTemplate).find(cap.capture(), eq(ChatMessageDocument.class));
        return cap.getValue();
    }
}
