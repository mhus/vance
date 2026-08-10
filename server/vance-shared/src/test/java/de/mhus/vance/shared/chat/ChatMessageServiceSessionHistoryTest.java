package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
 * {@link ChatMessageService#activeHistoryWithInterimForSession} — the
 * session-wide scrollback that makes a reload show the same worker notes the
 * live push already delivered (planning/process-visibility.md §5.3).
 *
 * <p>Same filter as the per-process variant (interim stays, removed goes),
 * but across every process — verified here by the query it issues, since the
 * process split lives in the repository method, not in the filter.
 */
class ChatMessageServiceSessionHistoryTest {

    private ChatMessageRepository repository;
    private ChatMessageService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        service = new ChatMessageService(
                repository,
                mock(MongoTemplate.class),
                mock(SessionService.class),
                mock(ApplicationEventPublisher.class));
    }

    @Test
    void sessionHistory_queriesWithoutProcessFilter() {
        when(repository.findByTenantIdAndSessionIdAndArchivedInMemoryIdIsNull(
                anyString(), anyString(), any(Sort.class)))
                .thenReturn(List.of());

        service.activeHistoryWithInterimForSession("t", "s");

        // The whole point: no thinkProcessId argument anywhere.
        verify(repository).findByTenantIdAndSessionIdAndArchivedInMemoryIdIsNull(
                eq("t"), eq("s"), any(Sort.class));
    }

    @Test
    void sessionHistory_keepsInterim_dropsRemoved() {
        when(repository.findByTenantIdAndSessionIdAndArchivedInMemoryIdIsNull(
                anyString(), anyString(), any(Sort.class)))
                .thenReturn(List.of(
                        canonical("u1", ChatRole.USER),
                        interim("i1"),
                        removed("r1"),
                        canonical("a1", ChatRole.ASSISTANT)));

        List<ChatMessageDocument> out = service.activeHistoryWithInterimForSession("t", "s");

        assertThat(out).extracting(ChatMessageDocument::getId)
                .containsExactly("u1", "i1", "a1");
    }

    @Test
    void sessionHistory_emptySession_returnsEmpty() {
        when(repository.findByTenantIdAndSessionIdAndArchivedInMemoryIdIsNull(
                anyString(), anyString(), any(Sort.class)))
                .thenReturn(List.of());

        assertThat(service.activeHistoryWithInterimForSession("t", "s")).isEmpty();
    }

    private static ChatMessageDocument canonical(String id, ChatRole role) {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .role(role).content("x").build();
        doc.setId(id);
        return doc;
    }

    private static ChatMessageDocument interim(String id) {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .role(ChatRole.ASSISTANT).content("working...").build();
        doc.setId(id);
        doc.getMeta().put(ChatMessageDocument.META_KIND, ChatMessageDocument.KIND_INTERIM);
        return doc;
    }

    private static ChatMessageDocument removed(String id) {
        ChatMessageDocument doc = ChatMessageDocument.builder()
                .role(ChatRole.ASSISTANT).content("gone").build();
        doc.setId(id);
        doc.getMeta().put(ChatMessageDocument.META_KIND, ChatMessageDocument.KIND_REMOVED);
        return doc;
    }
}
