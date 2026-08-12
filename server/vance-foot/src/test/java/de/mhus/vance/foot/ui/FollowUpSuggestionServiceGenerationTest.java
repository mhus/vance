package de.mhus.vance.foot.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.mhus.vance.api.chat.ChatMessageDto;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.foot.config.FootConfig;
import java.util.List;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code stateGeneration()} is what re-arms {@link LiveRegion}'s
 * once-per-idle-period fetch latch when a new assistant message arrives
 * while the user isn't typing. If it stopped moving, a suggestion would
 * only ever be fetched after the next keystroke.
 */
@SuppressWarnings("unchecked")
class FollowUpSuggestionServiceGenerationTest {

    private FollowUpSuggestionService service;

    @BeforeEach
    void setUp() {
        service = new FollowUpSuggestionService(
                new FootConfig(),
                mock(ObjectProvider.class),
                mock(SessionService.class));
    }

    @Test
    void conversationChange_bumpsTheGeneration() {
        long before = service.stateGeneration();

        service.onConversationChanged();

        assertThat(service.stateGeneration()).isGreaterThan(before);
    }

    @Test
    void everyConversationChange_bumpsAgain() {
        service.onConversationChanged();
        long afterFirst = service.stateGeneration();

        service.onConversationChanged();

        assertThat(service.stateGeneration()).isGreaterThan(afterFirst);
    }

    @Test
    void conversationChange_clearsAndBumps() {
        // A blank message clears the suggestion too; the animator must
        // see that state change rather than keep a stale latch.
        long before = service.stateGeneration();

        service.onConversationChanged();

        assertThat(service.stateGeneration()).isGreaterThan(before);
    }

    @Test
    void acceptingASuggestion_doesNotBumpTheGeneration() {
        // Accepting must NOT re-arm the latch: the accepted key is
        // suppressed for this assistant message, so re-firing would just
        // walk into the "already accepted" skip on every tick — the
        // behaviour the latch exists to stop.
        service.onConversationChanged();
        long afterMessage = service.stateGeneration();

        service.acceptCurrent();

        assertThat(service.stateGeneration()).isEqualTo(afterMessage);
    }

    @Test
    void clearingASuggestion_doesNotBumpTheGeneration() {
        // Typing already re-arms the latch via the input-activity
        // timestamp; bumping here as well would be a second, redundant
        // trigger source.
        service.onConversationChanged();
        long afterMessage = service.stateGeneration();

        service.clearSuggestion();

        assertThat(service.stateGeneration()).isEqualTo(afterMessage);
    }

    @Test
    void conversationContext_keepsSharedChatSpeakersAndNonAlternatingRoles() {
        String context = FollowUpSuggestionService.buildConversationContext(List.of(
                message("1", ChatRole.USER, "Deploy tonight?", "Alice", "chat"),
                message("2", ChatRole.USER, "Migration conflicts.", "Bob", "chat"),
                message("3", ChatRole.ASSISTANT, "Defer activation.", null, "chat")), "chat");

        assertThat(context).isEqualTo(
                "Alice [USER]:\nDeploy tonight?\n\n"
                        + "Bob [USER]:\nMigration conflicts.\n\n"
                        + "ASSISTANT:\nDefer activation.");
    }

    @Test
    void conversationContext_excludesWorkerMessages() {
        String context = FollowUpSuggestionService.buildConversationContext(List.of(
                message("1", ChatRole.USER, "Question", "Alice", "chat"),
                message("2", ChatRole.ASSISTANT, "Internal note", null, "worker")), "chat");

        assertThat(context).isEqualTo("Alice [USER]:\nQuestion");
    }

    private static ChatMessageDto message(
            String id, ChatRole role, String content, String sender, String process) {
        return ChatMessageDto.builder()
                .messageId(id)
                .thinkProcessId("think-" + id)
                .processName(process)
                .role(role)
                .content(content)
                .senderDisplayName(sender)
                .build();
    }

    @Test
    void unusedRestProvider_isNeverTouchedWithoutABoundSession() {
        // Guard order: no bound session → return before the REST client
        // is resolved. Keeps the fetch cheap on the animator thread.
        ObjectProvider<BrainRestClientService> restProvider = mock(ObjectProvider.class);
        SessionService sessions = mock(SessionService.class);  // current() → null
        FollowUpSuggestionService s = new FollowUpSuggestionService(
                new FootConfig(), restProvider, sessions);
        s.onConversationChanged();

        s.fetchIfApplicable();

        assertThat(s.currentSuggestion()).isNull();
        verifyNoInteractions(restProvider);
    }
}
