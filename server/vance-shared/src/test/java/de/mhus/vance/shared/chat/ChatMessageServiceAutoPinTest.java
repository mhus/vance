package de.mhus.vance.shared.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.prak.SpanStrength;
import de.mhus.vance.shared.session.SessionService;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Auto-pin of the first USER message per think-process. The user's
 * opening task is the mission and must survive compaction in every
 * mode (including EMERGENCY) — see
 * {@code planning/memory-compaction.md} §7 and
 * {@code specification/public/memory-knowledge-management.md} §10.
 *
 * <p>Mongo is mocked; we verify the tag set that {@code ChatMessageService}
 * mutated on {@link ChatMessageDocument#tags} before {@code repository.save}
 * was called.
 */
class ChatMessageServiceAutoPinTest {

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
        // save returns the same message — tests inspect its tag set
        // after append() runs.
        when(repository.save(any(ChatMessageDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void userMessage_emptyHistory_pinnedAsOriginalTask() {
        when(mongoTemplate.exists(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(false);
        ChatMessageDocument message = message(ChatRole.USER, "Hi, please help me refactor.");

        service.append(message);

        assertThat(message.getTags()).contains(SpanStrength.PINNED.tag());
    }

    @Test
    void userMessage_priorRowExists_notPinned() {
        when(mongoTemplate.exists(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(true);
        ChatMessageDocument message = message(ChatRole.USER, "follow-up question");

        service.append(message);

        assertThat(message.getTags())
                .doesNotContain(SpanStrength.PINNED.tag());
    }

    @Test
    void userMessage_existingStrengthTag_notOverridden() {
        when(mongoTemplate.exists(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(false);
        ChatMessageDocument message = message(ChatRole.USER, "deliberate downgrade");
        message.getTags().add(SpanStrength.WEAK.tag());

        service.append(message);

        assertThat(message.getTags())
                .contains(SpanStrength.WEAK.tag())
                .doesNotContain(SpanStrength.PINNED.tag());
        // exists() was never queried — the strength-tag short-circuit
        // happens before the Mongo round-trip, which keeps the hot path
        // cheap for any caller that already classified the message.
        verify(mongoTemplate, never()).exists(any(Query.class), eq(ChatMessageDocument.class));
    }

    @Test
    void userMessage_alreadyPinned_idempotent() {
        when(mongoTemplate.exists(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(false);
        ChatMessageDocument message = message(ChatRole.USER, "explicitly pinned");
        message.getTags().add(SpanStrength.PINNED.tag());

        service.append(message);

        // Exactly one PINNED tag in the set (Set semantics) — the auto-pin
        // path short-circuited because hasStrengthTag returned true.
        long pinnedCount = message.getTags().stream()
                .filter(SpanStrength.PINNED.tag()::equals)
                .count();
        assertThat(pinnedCount).isEqualTo(1);
    }

    @Test
    void assistantMessage_neverPinnedEvenIfFirstRow() {
        when(mongoTemplate.exists(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(false);
        ChatMessageDocument message = message(ChatRole.ASSISTANT, "system greeting");

        service.append(message);

        assertThat(message.getTags())
                .doesNotContain(SpanStrength.PINNED.tag());
        // Non-USER short-circuits before the exists() round-trip.
        verify(mongoTemplate, never()).exists(any(Query.class), eq(ChatMessageDocument.class));
    }

    @Test
    void systemMessage_neverPinned() {
        when(mongoTemplate.exists(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(false);
        ChatMessageDocument message = message(ChatRole.SYSTEM, "engine bootstrap");

        service.append(message);

        assertThat(message.getTags())
                .doesNotContain(SpanStrength.PINNED.tag());
    }

    @Test
    void missingTenantId_skipsAutoPin() {
        ChatMessageDocument message = ChatMessageDocument.builder()
                .thinkProcessId("proc-1")
                .role(ChatRole.USER)
                .content("missing tenant")
                .tags(new LinkedHashSet<>())
                .build();

        service.append(message);

        assertThat(message.getTags())
                .doesNotContain(SpanStrength.PINNED.tag());
        verify(mongoTemplate, never()).exists(any(Query.class), eq(ChatMessageDocument.class));
    }

    @Test
    void missingProcessId_skipsAutoPin() {
        ChatMessageDocument message = ChatMessageDocument.builder()
                .tenantId("acme")
                .role(ChatRole.USER)
                .content("missing process")
                .tags(new LinkedHashSet<>())
                .build();

        service.append(message);

        assertThat(message.getTags())
                .doesNotContain(SpanStrength.PINNED.tag());
        verify(mongoTemplate, never()).exists(any(Query.class), eq(ChatMessageDocument.class));
    }

    @Test
    void nullTagSet_handledGracefully_andPinned() {
        when(mongoTemplate.exists(any(Query.class), eq(ChatMessageDocument.class)))
                .thenReturn(false);
        // Mimic a caller that explicitly set tags to null (unusual but
        // not impossible if the builder is bypassed).
        ChatMessageDocument message = ChatMessageDocument.builder()
                .tenantId("acme")
                .thinkProcessId("proc-1")
                .role(ChatRole.USER)
                .content("opening task")
                .build();
        message.setTags(null);

        service.append(message);

        assertThat(message.getTags())
                .isNotNull()
                .contains(SpanStrength.PINNED.tag());
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static ChatMessageDocument message(ChatRole role, String content) {
        return ChatMessageDocument.builder()
                .tenantId("acme")
                .sessionId("sess-42")
                .thinkProcessId("proc-1")
                .role(role)
                .content(content)
                .tags(new LinkedHashSet<>())
                .build();
    }
}
