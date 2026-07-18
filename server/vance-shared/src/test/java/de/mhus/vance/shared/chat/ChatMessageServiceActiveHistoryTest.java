package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.session.SessionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Verifies the interim-filter contract on
 * {@link ChatMessageService#activeHistory} vs.
 * {@link ChatMessageService#activeHistoryWithInterim}: the LLM-replay
 * path must never see {@code meta.kind=interim} messages (Frankie's
 * live working-log), the UI-scrollback path must keep them.
 */
class ChatMessageServiceActiveHistoryTest {

    private ChatMessageRepository repository;
    private ChatMessageService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        SessionService sessionService = mock(SessionService.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        service = new ChatMessageService(repository, mongoTemplate, sessionService, eventPublisher);
    }

    @Test
    void activeHistory_dropsInterimMessages_keepsCanonical() {
        ChatMessageDocument user = canonical("u1", ChatRole.USER, "hi");
        ChatMessageDocument interim1 = interim("i1", "Lass mich erst verstehen...");
        ChatMessageDocument interim2 = interim("i2", "Jetzt schaue ich...");
        ChatMessageDocument canonical = canonical("a1", ChatRole.ASSISTANT, "Hier die Zusammenfassung");
        when(repository.findByTenantIdAndSessionIdAndThinkProcessIdAndArchivedInMemoryIdIsNull(
                anyString(), anyString(), anyString(), any(Sort.class)))
                .thenReturn(List.of(user, interim1, interim2, canonical));

        List<ChatMessageDocument> out = service.activeHistory("t", "s", "p");

        assertThat(out).extracting(ChatMessageDocument::getId).containsExactly("u1", "a1");
    }

    @Test
    void activeHistory_emptyInput_returnsEmpty() {
        when(repository.findByTenantIdAndSessionIdAndThinkProcessIdAndArchivedInMemoryIdIsNull(
                anyString(), anyString(), anyString(), any(Sort.class)))
                .thenReturn(List.of());

        assertThat(service.activeHistory("t", "s", "p")).isEmpty();
    }

    @Test
    void activeHistoryWithInterim_keepsInterimMessages() {
        ChatMessageDocument user = canonical("u1", ChatRole.USER, "hi");
        ChatMessageDocument interim1 = interim("i1", "Lass mich erst verstehen...");
        ChatMessageDocument canonical = canonical("a1", ChatRole.ASSISTANT, "Final");
        when(repository.findByTenantIdAndSessionIdAndThinkProcessIdAndArchivedInMemoryIdIsNull(
                anyString(), anyString(), anyString(), any(Sort.class)))
                .thenReturn(List.of(user, interim1, canonical));

        List<ChatMessageDocument> out = service.activeHistoryWithInterim("t", "s", "p");

        assertThat(out).extracting(ChatMessageDocument::getId)
                .containsExactly("u1", "i1", "a1");
    }

    @Test
    void chatMessageDocument_isInterim_reflectsMetaKind() {
        ChatMessageDocument plain = canonical("x", ChatRole.ASSISTANT, "");
        assertThat(plain.isInterim()).isFalse();

        ChatMessageDocument marked = interim("y", "");
        assertThat(marked.isInterim()).isTrue();
    }

    private static ChatMessageDocument canonical(String id, ChatRole role, String content) {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .role(role)
                .content(content)
                .build();
        doc.setId(id);
        return doc;
    }

    private static ChatMessageDocument interim(String id, String content) {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .role(ChatRole.ASSISTANT)
                .content(content)
                .build();
        doc.setId(id);
        doc.getMeta().put(ChatMessageDocument.META_KIND, ChatMessageDocument.KIND_INTERIM);
        return doc;
    }
}
