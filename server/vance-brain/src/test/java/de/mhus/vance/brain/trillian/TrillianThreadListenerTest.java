package de.mhus.vance.brain.trillian;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.inbox.MaximegalonCreatedEvent;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.shared.inbox.MaximegalonMessageEvent;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Who a contribution wakes: only a conversation of a live multi-headed
 * Trillian, written by someone else. Everything else — asks, other people's
 * threads, the Trillian's own answers, single-headed Natures — must not
 * spawn or wake a head, because a thread reaching both a head and the loop
 * would be answered twice, and a head on an ask would be a chat where a
 * decision is owed.
 */
class TrillianThreadListenerTest {

    private static final String ACCOUNT = "_trillian-void-1234";
    private static final String TENANT = "acme";

    private final TrillianOwnSessions ownSessions = mock(TrillianOwnSessions.class);
    private final TrillianNatureRegistry natureRegistry = mock(TrillianNatureRegistry.class);
    private final TrillianHeadService headService = mock(TrillianHeadService.class);
    private final TrillianThreadListener listener =
            new TrillianThreadListener(ownSessions, natureRegistry, headService);

    @Test
    void aContributionToAMultiHeadTrillian_reachesTheHeadService() {
        givenLiveMultiHeadLoop("loop-1");

        listener.onThreadMessage(messageEvent(conversation(), message("m-1", "bob", "Where are the docs?")));

        verify(headService).dispatchContribution(any(), any(), any(), any());
    }

    @Test
    void theTrilliansOwnAnswer_wakesNobody() {
        givenLiveMultiHeadLoop("loop-1");

        listener.onThreadMessage(messageEvent(conversation(), message("m-1", ACCOUNT, "Here they are.")));

        verify(headService, never()).dispatchContribution(any(), any(), any(), any());
    }

    @Test
    void aSingleHeadedNature_answersThreadsOnItsOwnLoop_notThroughAHead() {
        givenLiveLoop("loop-1", /* multiHead */ false);

        listener.onThreadMessage(messageEvent(conversation(), message("m-1", "bob", "Where are the docs?")));

        verify(headService, never()).dispatchContribution(any(), any(), any(), any());
    }

    @Test
    void anAsk_neverReachesAHead() {
        givenLiveMultiHeadLoop("loop-1");
        MaximegalonDocument ask = conversation();
        ask.setRequiresAction(true);

        listener.onThreadMessage(messageEvent(ask, message("m-1", "bob", "Approve this?")));

        verify(headService, never()).dispatchContribution(any(), any(), any(), any());
    }

    @Test
    void aThreadAssignedToAHuman_isNotATrillianConversation() {
        MaximegalonDocument humanThread = conversation();
        humanThread.setAssignedToUserId("mara");

        listener.onThreadMessage(messageEvent(humanThread, message("m-1", "bob", "Hi")));

        verify(headService, never()).dispatchContribution(any(), any(), any(), any());
    }

    @Test
    void anArchivedThread_isNotAConversationAnymore() {
        givenLiveMultiHeadLoop("loop-1");
        MaximegalonDocument archived = conversation();
        archived.setStatus(MaximegalonStatus.ARCHIVED);

        listener.onThreadMessage(messageEvent(archived, message("m-1", "bob", "Hi")));

        verify(headService, never()).dispatchContribution(any(), any(), any(), any());
    }

    @Test
    void aThreadWithoutALiveLoop_rests() {
        when(ownSessions.liveLoopOf(TENANT, ACCOUNT)).thenReturn(Optional.empty());

        listener.onThreadMessage(messageEvent(conversation(), message("m-1", "bob", "Hi")));

        verify(headService, never()).dispatchContribution(any(), any(), any(), any());
    }

    @Test
    void aThreadCreatedWithItsOpener_embedded_reachesTheHeadService() {
        givenLiveMultiHeadLoop("loop-1");
        MaximegalonDocument created = conversation();
        created.setMessages(new ArrayList<>(List.of(message("m-1", "bob", "Opener"))));

        listener.onThreadCreated(new MaximegalonCreatedEvent(created));

        verify(headService).dispatchContribution(any(), any(), any(), any());
    }

    @Test
    void aThreadCreatedEmpty_wakesNobody() {
        givenLiveMultiHeadLoop("loop-1");
        MaximegalonDocument created = conversation();
        created.setMessages(null);

        listener.onThreadCreated(new MaximegalonCreatedEvent(created));

        verify(headService, never()).dispatchContribution(any(), any(), any(), any());
    }

    @Test
    void aFailingDispatch_isContained_notPropagatedToTheWriter() {
        givenLiveMultiHeadLoop("loop-1");
        Mockito.doThrow(new IllegalStateException("boom"))
                .when(headService)
                .dispatchContribution(any(), any(), any(), any());

        // The listener runs synchronously on the appending thread; a head
        // problem must not fail the contribution write that already landed.
        listener.onThreadMessage(messageEvent(conversation(), message("m-1", "bob", "Hi")));

        verify(headService).dispatchContribution(any(), any(), any(), any());
    }

    // ──────────────────── Fixtures ────────────────────

    private void givenLiveMultiHeadLoop(String loopId) {
        givenLiveLoop(loopId, /* multiHead */ true);
    }

    private void givenLiveLoop(String loopId, boolean multiHead) {
        TrillianNature nature = mock(TrillianNature.class);
        when(nature.multiHead()).thenReturn(multiHead);
        when(natureRegistry.resolve(any())).thenReturn(nature);
        ThinkProcessDocument loop = new ThinkProcessDocument();
        loop.setId(loopId);
        loop.setTenantId(TENANT);
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_NATURE, "void");
        loop.setEngineParams(params);
        when(ownSessions.liveLoopOf(TENANT, ACCOUNT)).thenReturn(Optional.of(loop));
    }

    private static MaximegalonDocument conversation() {
        MaximegalonDocument item = new MaximegalonDocument();
        item.setId("thread-1");
        item.setTenantId(TENANT);
        item.setAssignedToUserId(ACCOUNT);
        item.setOriginatorUserId("bob");
        item.setType(MaximegalonType.OUTPUT_TEXT);
        item.setStatus(MaximegalonStatus.PENDING);
        return item;
    }

    private static MaximegalonMessage message(String id, String author, String body) {
        return MaximegalonMessage.builder()
                .id(id)
                .authorUserId(author)
                .body(body)
                .createdAt(Instant.now())
                .readBy(new ArrayList<>())
                .reactions(new ArrayList<>())
                .build();
    }

    private static MaximegalonMessageEvent messageEvent(MaximegalonDocument item, MaximegalonMessage message) {
        return new MaximegalonMessageEvent(item, message);
    }
}
