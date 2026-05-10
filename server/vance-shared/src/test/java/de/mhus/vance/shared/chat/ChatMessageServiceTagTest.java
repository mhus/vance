package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.shared.session.SessionService;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Tag mutations on {@link ChatMessageService}. Mongo is mocked — we
 * verify which atomic update is dispatched and that the wire shape uses
 * {@code $addToSet} with {@code $each}, the contract that guarantees
 * idempotent, race-safe tag addition.
 *
 * <p>We inspect the captured {@link Update} via its serialised JSON
 * form rather than casting Spring-Data internal modifier wrappers
 * ({@code Update.Each}) — that lets the test assert the actual driver
 * wire payload without coupling to Spring Data internals.
 */
class ChatMessageServiceTagTest {

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
    void tag_emptyTagSet_skipsMongoUpdate() {
        service.tag("m-1", Set.of());

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), eq(ChatMessageDocument.class));
    }

    @Test
    void tag_blankMessageId_skipsMongoUpdate() {
        service.tag("  ", Set.of("FILE_EDIT"));

        verify(mongoTemplate, never()).updateFirst(
                any(Query.class), any(Update.class), eq(ChatMessageDocument.class));
    }

    @Test
    void tag_singleTag_issuesAddToSetEach() {
        service.tag("m-1", Set.of("FILE_EDIT"));

        String json = captureUpdateJson();
        // Driver payload must use $addToSet + $each so concurrent adds
        // dedup automatically and never lose data.
        assertThat(json).contains("\"$addToSet\"")
                        .contains("\"tags\"")
                        .contains("\"$each\"")
                        .contains("\"FILE_EDIT\"");
    }

    @Test
    void tag_multipleTags_preservesInsertionOrderInEachArray() {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("TOOL_CALL:client_file_edit");
        tags.add("RESOURCE:CLIENT_FILE:/abs/Foo.java");
        tags.add("FILE_EDIT");

        service.tag("m-1", tags);

        String json = captureUpdateJson();
        int p1 = json.indexOf("TOOL_CALL:client_file_edit");
        int p2 = json.indexOf("RESOURCE:CLIENT_FILE:/abs/Foo.java");
        int p3 = json.indexOf("FILE_EDIT");
        assertThat(p1).isPositive();
        assertThat(p2).isGreaterThan(p1);
        assertThat(p3).isGreaterThan(p2);
    }

    @Test
    void tag_queryTargetsExactMessageId() {
        service.tag("m-42", Set.of("ERROR"));

        ArgumentCaptor<Query> queryCap = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).updateFirst(queryCap.capture(), any(Update.class),
                eq(ChatMessageDocument.class));

        Document queryDoc = queryCap.getValue().getQueryObject();
        assertThat(queryDoc.get("_id")).isEqualTo("m-42");
    }

    /**
     * Inspect the Update via {@link Update#toString()} — Spring Data's
     * own debug representation that does serialise the {@code Each}
     * modifier. Avoids the missing-BSON-codec issue of plain
     * {@code getUpdateObject().toJson()} and avoids reflection on
     * Spring-Data internals.
     */
    private String captureUpdateJson() {
        ArgumentCaptor<Update> cap = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).updateFirst(any(Query.class), cap.capture(),
                eq(ChatMessageDocument.class));
        return cap.getValue().toString();
    }
}
