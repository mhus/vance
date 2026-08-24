package de.mhus.vance.shared.thinkprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.thinkprocess.ActiveAppContext;
import de.mhus.vance.api.thinkprocess.ActiveInboxContext;
import de.mhus.vance.api.thinkprocess.BoundDocSelection;
import de.mhus.vance.shared.enginemessage.EngineMessageDocument;
import de.mhus.vance.shared.enginemessage.EngineMessageService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The per-turn context hints across the {@link EngineMessageDocument} hop.
 *
 * <p>This hop is a hand-written field-for-field mapping in both directions, and
 * it is the durable step between the WS handler and the engine drain — so a
 * field missing from it is a field the engine never sees, with no error
 * anywhere. That is exactly how {@code activeInbox} came to be sent by the
 * client, stored in the wire DTO, carried by the codec, and still absent from
 * the prompt: four representations agreed and the fifth quietly dropped it.
 *
 * <p>The test therefore asserts the hints as a group. A new one added to
 * {@code PendingMessageDocument} without touching the two mappings will fail
 * here rather than in a browser.
 */
class ThinkProcessServicePendingHopTest {

    private static final String PROCESS = "p-1";

    private ThinkProcessRepository repository;
    private EngineMessageService engineMessages;
    private ThinkProcessService service;

    @BeforeEach
    void setUp() {
        repository = mock(ThinkProcessRepository.class);
        engineMessages = mock(EngineMessageService.class);
        service = new ThinkProcessService(
                repository,
                mock(MongoTemplate.class),
                mock(ApplicationEventPublisher.class),
                engineMessages);
        ThinkProcessDocument target = ThinkProcessDocument.builder()
                .id(PROCESS).tenantId("acme").build();
        when(repository.findById(PROCESS)).thenReturn(Optional.of(target));
    }

    private static PendingMessageDocument turnWithHints() {
        return PendingMessageDocument.builder()
                .type(PendingMessageType.USER_CHAT_INPUT)
                .at(Instant.parse("2026-08-24T10:00:00Z"))
                .fromUser("wile.coyote")
                .fromUserDisplayName("Wile E. Coyote")
                .content("what about this one?")
                .voiceMode(false)
                .activeApp(ActiveAppContext.builder().folder("f").app("kanban").build())
                .boundDocumentId("doc-1")
                .boundDocSelection(BoundDocSelection.builder().from(1).to(9).build())
                .activeInbox(ActiveInboxContext.builder()
                        .threadId("t1").messageId("m7").build())
                .build();
    }

    @Test
    void appendPending_carriesEveryTurnHintIntoTheEngineMessage() {
        service.appendPending(PROCESS, turnWithHints());

        ArgumentCaptor<EngineMessageDocument> captor =
                ArgumentCaptor.forClass(EngineMessageDocument.class);
        verify(engineMessages).acceptDelivery(captor.capture());
        EngineMessageDocument stored = captor.getValue();

        assertThat(stored.getFromUserDisplayName()).isEqualTo("Wile E. Coyote");
        assertThat(stored.getActiveApp()).isNotNull();
        assertThat(stored.getBoundDocumentId()).isEqualTo("doc-1");
        assertThat(stored.getBoundDocSelection()).isNotNull();
        // The one that was missing.
        assertThat(stored.getActiveInbox()).isNotNull();
        assertThat(stored.getActiveInbox().getThreadId()).isEqualTo("t1");
        assertThat(stored.getActiveInbox().getMessageId()).isEqualTo("m7");
    }

    @Test
    void drainPending_bringsEveryTurnHintBackOut() {
        service.appendPending(PROCESS, turnWithHints());
        ArgumentCaptor<EngineMessageDocument> captor =
                ArgumentCaptor.forClass(EngineMessageDocument.class);
        verify(engineMessages).acceptDelivery(captor.capture());
        when(engineMessages.drainInbox(PROCESS))
                .thenReturn(List.of(captor.getValue()));

        List<PendingMessageDocument> drained = service.drainPending(PROCESS);

        assertThat(drained).hasSize(1);
        PendingMessageDocument back = drained.get(0);
        assertThat(back.getFromUserDisplayName()).isEqualTo("Wile E. Coyote");
        assertThat(back.getActiveApp()).isNotNull();
        assertThat(back.getBoundDocumentId()).isEqualTo("doc-1");
        assertThat(back.getBoundDocSelection()).isNotNull();
        assertThat(back.getActiveInbox()).isNotNull();
        assertThat(back.getActiveInbox().getThreadId()).isEqualTo("t1");
        assertThat(back.getActiveInbox().getMessageId()).isEqualTo("m7");
    }
}
