package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.session.SessionService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class ChatMessageServiceTest {

    private final ChatMessageRepository repository = mock(ChatMessageRepository.class);
    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final SessionService sessionService = mock(SessionService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final ChatMessageService service = new ChatMessageService(
            repository, mongoTemplate, sessionService, eventPublisher);

    @Test
    void searchContentByText_returnsEmpty_forEmptySessionSet_withoutQuerying() {
        assertThat(service.searchContentByText("acme", Set.of(), "query", 10)).isEmpty();
        verify(mongoTemplate, times(0)).find(any(Query.class), eq(ChatMessageDocument.class));
    }

    @Test
    void searchContentByText_returnsTextIndexHits_withoutRegexFallback() {
        ChatMessageDocument hit = ChatMessageDocument.builder().content("match").build();
        when(mongoTemplate.find(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(List.of(hit));

        List<ChatMessageDocument> result =
                service.searchContentByText("acme", Set.of("s-1"), "match", 10);

        assertThat(result).containsExactly(hit);
        // Text index found something → the regex fallback must NOT run.
        verify(mongoTemplate, times(1)).find(any(Query.class), eq(ChatMessageDocument.class));
    }

    @Test
    void searchContentByText_fallsBackToRegex_whenTextIndexEmpty() {
        ChatMessageDocument regexHit = ChatMessageDocument.builder().content("Hi").build();
        when(mongoTemplate.find(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(List.of())        // text index: nothing
                .thenReturn(List.of(regexHit)); // regex fallback: hit

        List<ChatMessageDocument> result =
                service.searchContentByText("acme", Set.of("s-1"), "Hi", 10);

        assertThat(result).containsExactly(regexHit);
        // Two queries: text index, then the regex fallback.
        verify(mongoTemplate, times(2)).find(any(Query.class), eq(ChatMessageDocument.class));
    }
}
