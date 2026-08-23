package de.mhus.vance.brain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.chat.SessionCropRequest;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Crop is chat-process-scoped on both sides.
 *
 * <p>The read side always was ("the user must not crop a worker's
 * transcript"); the write side matched on {@code (id, tenant, session)}
 * only. Since the scrollback started returning worker rows <em>with</em>
 * their {@code messageId}, those ids are in every client's hands — and a
 * running worker would silently lose parts of its own replay history.
 */
class ChatHistoryCropScopeTest {

    private static final String TENANT = "acme";
    private static final String SESSION = "sess-1";
    private static final String OWNER = "marvin";
    private static final String CHAT_PROCESS = "chat-1";

    private final SessionService sessionService = mock(SessionService.class);
    private final ChatMessageService chatMessageService = mock(ChatMessageService.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final RequestAuthority authority = mock(RequestAuthority.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    private final ChatHistoryController controller = new ChatHistoryController(
            sessionService, chatMessageService, thinkProcessService, authority);

    @BeforeEach
    void setUp() {
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(OWNER);
        when(sessionService.findBySessionId(SESSION)).thenReturn(Optional.of(
                SessionDocument.builder()
                        .sessionId(SESSION)
                        .tenantId(TENANT)
                        .projectId("proj-1")
                        .userId(OWNER)
                        .chatProcessId(CHAT_PROCESS)
                        .build()));
        when(chatMessageService.historyForCrop(TENANT, SESSION, CHAT_PROCESS))
                .thenReturn(List.of(message("m1"), message("m2")));
    }

    @Test
    void crop_ignoresIdsBelongingToAnotherProcessOfTheSession() {
        controller.crop(TENANT, SESSION,
                SessionCropRequest.builder().remove(List.of("m1", "worker-9")).build(),
                request);

        ArgumentCaptor<java.util.Collection<String>> removed = ArgumentCaptor.captor();
        verify(chatMessageService).markRemoved(eq(TENANT), eq(SESSION), removed.capture());
        assertThat(removed.getValue()).containsExactly("m1");
    }

    @Test
    void crop_withNothingCroppableLeftDoesNotWriteAtAll() {
        controller.crop(TENANT, SESSION,
                SessionCropRequest.builder().remove(List.of("worker-9")).build(),
                request);

        verify(chatMessageService, never()).markRemoved(any(), any(), any());
    }

    @Test
    void crop_restoreIsScopedTheSameWay() {
        controller.crop(TENANT, SESSION,
                SessionCropRequest.builder().restore(List.of("m2", "worker-9")).build(),
                request);

        ArgumentCaptor<java.util.Collection<String>> restored = ArgumentCaptor.captor();
        verify(chatMessageService).unmarkRemoved(eq(TENANT), eq(SESSION), restored.capture());
        assertThat(restored.getValue()).containsExactly("m2");
    }

    private static ChatMessageDocument message(String id) {
        return ChatMessageDocument.builder()
                .id(id)
                .tenantId(TENANT)
                .sessionId(SESSION)
                .thinkProcessId(CHAT_PROCESS)
                .role(ChatRole.USER)
                .content("hello " + id)
                .build();
    }
}
