package de.mhus.vance.brain.trillian.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.InboxMessagePostRequest;
import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.trillian.TrillianHeadService;
import de.mhus.vance.brain.trillian.TrillianOwnSessions;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.inbox.MaximegalonArchivedEvent;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.shared.inbox.MaximegalonMessageEvent;
import de.mhus.vance.shared.inbox.MaximegalonRuleException;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.ToolException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The gates of the protocol entry: who may open, who may write, what stays
 * readable. Every refusal carries a stable reason — the shells show it to
 * the external agent as text, so it must be worth grepping for.
 */
@ExtendWith(MockitoExtension.class)
class TrillianConversationServiceTest {

    private static final String TENANT = "acme";
    private static final String CALLER = "road.runner";
    private static final String COLIN = "_trillian-colin-5127";
    private static final String OTHER = "wile.coyote";

    @Mock
    private MaximegalonService threads;

    @Mock
    private TrillianExposureService exposureService;

    @Mock
    private TrillianOwnSessions ownSessions;

    @Mock
    private TrillianNatureRegistry natureRegistry;

    @Mock
    private TrillianHeadService headService;

    private TrillianConversationService service;

    @BeforeEach
    void setUp() {
        service = new TrillianConversationService(threads, exposureService, ownSessions, natureRegistry, headService);
        TrillianNature multiHead = mock(TrillianNature.class);
        lenient().when(multiHead.multiHead()).thenReturn(true);
        lenient().when(multiHead.headLimit()).thenReturn(5);
        lenient().when(natureRegistry.resolve(anyString())).thenReturn(multiHead);
        lenient().when(ownSessions.liveLoopOf(TENANT, COLIN)).thenReturn(Optional.of(new ThinkProcessDocument()));
        // A running conversation passes the gate again on every call.
        lenient()
                .when(exposureService.findByAccount(TENANT, COLIN))
                .thenReturn(Optional.of(new TrillianExposureService.Exposed(COLIN, colinConfig(CALLER))));
    }

    // ──────────────────── open ────────────────────

    @Test
    void openCreatesTheConversationThreadThroughTheEntry() {
        givenExposure(colinConfig("road.runner"), true);
        when(headService.hasHeadCapacity(any(), any())).thenReturn(true);
        when(threads.create(any())).thenAnswer(inv -> {
            MaximegalonDocument doc = inv.getArgument(0);
            doc.setId("thread-1");
            return doc;
        });

        TrillianConversationService.Opened opened =
                service.open(TENANT, CALLER, "colin", "Hello", "A first contribution.");

        assertThat(opened.conversationId()).isEqualTo("thread-1");
        ArgumentCaptor<MaximegalonDocument> thread = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(threads).create(thread.capture());
        MaximegalonDocument created = thread.getValue();
        assertThat(created.getOriginatorUserId()).isEqualTo(CALLER);
        assertThat(created.getAssignedToUserId()).isEqualTo(COLIN);
        assertThat(created.getType()).isEqualTo(MaximegalonType.OUTPUT_TEXT);
        assertThat(created.getTags()).containsExactly("discussion");
        assertThat(created.isRequiresAction()).isFalse();
        assertThat(created.getTitle()).isEqualTo("Hello");
        assertThat(created.getBody()).isEqualTo("A first contribution.");
    }

    @Test
    void openWithoutTitleDerivesItFromTheFirstLine() {
        givenExposure(colinConfig("road.runner"), true);
        when(headService.hasHeadCapacity(any(), any())).thenReturn(true);
        when(threads.create(any())).thenAnswer(inv -> inv.getArgument(0));

        service.open(TENANT, CALLER, "colin", null, "First line is the title\nand this is the rest.");

        ArgumentCaptor<MaximegalonDocument> thread = ArgumentCaptor.forClass(MaximegalonDocument.class);
        verify(threads).create(thread.capture());
        assertThat(thread.getValue().getTitle()).isEqualTo("First line is the title");
    }

    @Test
    void openRefusesAnUnknownAgent() {
        when(exposureService.findByExposedName(TENANT, "nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "nobody", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("agent not exposed");
        verify(threads, never()).create(any());
    }

    @Test
    void openRefusesWhenTheDoorIsClosed_asNotExposed() {
        // No enumeration: a closed door answers like an agent that is not there.
        givenExposure(colinConfig("road.runner"), false);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessage("agent not exposed: 'colin'");
        verify(threads, never()).create(any());
    }

    @Test
    void openRefusesACounterpartThatIsNotAllowed_asNotExposed() {
        givenExposure(colinConfig(OTHER), true);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessage("agent not exposed: 'colin'");
        verify(threads, never()).create(any());
    }

    @Test
    void openRefusesAnInactiveAgent() {
        givenExposure(colinConfig("road.runner"), true);
        when(ownSessions.liveLoopOf(TENANT, COLIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("agent inactive");
        verify(threads, never()).create(any());
    }

    @Test
    void openIsHonestAboutTheConversationLimit() {
        givenExposure(colinConfig("road.runner"), true);
        when(headService.hasHeadCapacity(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation limit reached");
        verify(threads, never()).create(any());
    }

    @Test
    void aSingleHeadedAgent_isReachable_itsLoopAnswersTheThread() {
        // No heads, no head limit: the thread lands in the Trillian's inbox
        // and its own loop answers it (inbox_unread).
        givenExposure(colinConfig("road.runner"), true);
        TrillianNature singleHead = mock(TrillianNature.class);
        when(singleHead.multiHead()).thenReturn(false);
        when(natureRegistry.resolve(anyString())).thenReturn(singleHead);
        when(threads.create(any())).thenAnswer(inv -> {
            MaximegalonDocument doc = inv.getArgument(0);
            doc.setId("thread-9");
            return doc;
        });

        assertThat(service.open(TENANT, CALLER, "colin", null, "Hi").conversationId())
                .isEqualTo("thread-9");
        verify(headService, never()).hasHeadCapacity(any(), any());
    }

    // ──────────────────── send & read ────────────────────

    @Test
    void sendContributesAndReturnsTheMessage() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING, message("m-1", COLIN, "earlier"));
        when(threads.post(TENANT, "thread-1", CALLER, "and more", null))
                .thenReturn(posted(
                        conversation(
                                "thread-1",
                                CALLER,
                                MaximegalonStatus.PENDING,
                                message("m-1", COLIN, "earlier"),
                                message("m-2", CALLER, "and more")),
                        message("m-2", CALLER, "and more")));

        TrillianConversationService.Contribution sent = service.send(TENANT, CALLER, "thread-1", "and more");

        assertThat(sent.id()).isEqualTo("m-2");
        assertThat(sent.author()).isEqualTo(CALLER);
        assertThat(sent.body()).isEqualTo("and more");
    }

    @Test
    void sendTakesTheMessageItWroteAsCursor_notTheCallersLastOne() {
        // A parallel send of the same caller landed after this one, and the
        // head answered in between: the cursor is the message this call
        // wrote, so the answer to it stays ahead of the cursor.
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING, message("m-1", COLIN, "earlier"));
        when(threads.post(TENANT, "thread-1", CALLER, "and more", null))
                .thenReturn(posted(
                        conversation(
                                "thread-1",
                                CALLER,
                                MaximegalonStatus.PENDING,
                                message("m-1", COLIN, "earlier"),
                                message("m-2", CALLER, "and more"),
                                message("m-3", COLIN, "the answer to m-2"),
                                message("m-4", CALLER, "a parallel send")),
                        message("m-2", CALLER, "and more")));

        assertThat(service.send(TENANT, CALLER, "thread-1", "and more").id()).isEqualTo("m-2");
    }

    @Test
    void sendAtTheMessageLimit_isConversationFull() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        when(threads.post(TENANT, "thread-1", CALLER, "one more", null))
                .thenThrow(new MaximegalonRuleException(MaximegalonRuleException.MESSAGE_LIMIT_REACHED, "full"));

        assertThatThrownBy(() -> service.send(TENANT, CALLER, "thread-1", "one more"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation full");
    }

    @Test
    void sendRefusesARevokedCounterpart() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        when(exposureService.findByAccount(TENANT, COLIN))
                .thenReturn(Optional.of(new TrillianExposureService.Exposed(COLIN, colinConfig(OTHER))));

        assertThatThrownBy(() -> service.send(TENANT, CALLER, "thread-1", "still there?"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("agent not exposed");
        verify(threads, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void sendRefusesWhenTheAgentIsNoLongerExposed() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        when(exposureService.findByAccount(TENANT, COLIN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send(TENANT, CALLER, "thread-1", "hello?"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("agent not exposed");
        verify(threads, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void readRefusesWhenTheDoorWasClosed() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING, message("m-1", COLIN, "hi"));
        TrillianExposureConfig closed = new TrillianExposureConfig(
                "colin", "Colin", List.of(CALLER), 0, 0, new TrillianExposureConfig.Doors(false, false));
        when(exposureService.findByAccount(TENANT, COLIN))
                .thenReturn(Optional.of(new TrillianExposureService.Exposed(COLIN, closed)));

        assertThatThrownBy(() -> service.read(TENANT, CALLER, "thread-1", null, 0))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("agent not exposed");
    }

    @Test
    void aNoticeOnTheTrilliansDesk_isNotAConversationOfTheDoor() {
        MaximegalonDocument notice = conversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        notice.setTags(new ArrayList<>());
        when(threads.findById(TENANT, "thread-1")).thenReturn(Optional.of(notice));

        assertThatThrownBy(() -> service.read(TENANT, CALLER, "thread-1", null, 0))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation not found");
    }

    @Test
    void closeArchivesTheConversation_asTheCaller() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING);

        service.close(TENANT, CALLER, "thread-1");

        verify(threads).archive(TENANT, "thread-1", CALLER);
    }

    @Test
    void closingTwice_isNoError() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.ARCHIVED);

        service.close(TENANT, CALLER, "thread-1");

        verify(threads, never()).archive(any(), any(), any());
    }

    @Test
    void closeRefusesSomeoneElsesConversation() {
        givenConversation("thread-1", OTHER, MaximegalonStatus.PENDING);

        assertThatThrownBy(() -> service.close(TENANT, CALLER, "thread-1"))
                .isInstanceOf(ToolException.class)
                .hasMessage("conversation not found: thread-1");
        verify(threads, never()).archive(any(), any(), any());
    }

    @Test
    void sendRefusesSomeoneElsesConversation() {
        givenConversation("thread-1", OTHER, MaximegalonStatus.PENDING);

        assertThatThrownBy(() -> service.send(TENANT, CALLER, "thread-1", "hi"))
                .isInstanceOf(ToolException.class)
                .hasMessage("conversation not found: thread-1");
        verify(threads, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void sendRefusesAnArchivedConversation() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.ARCHIVED);

        assertThatThrownBy(() -> service.send(TENANT, CALLER, "thread-1", "hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation archived");
        verify(threads, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void readKeepsWorkingAfterArchive() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.ARCHIVED, message("m-1", COLIN, "all done"));

        TrillianConversationService.Reply reply = service.read(TENANT, CALLER, "thread-1", null, 0);

        assertThat(reply.arrived()).isTrue();
        assertThat(reply.messages()).hasSize(1);
        assertThat(reply.messages().get(0).body()).isEqualTo("all done");
    }

    @Test
    void readAnswersOnlyWhatFollowsTheCursor() {
        givenConversation(
                "thread-1",
                CALLER,
                MaximegalonStatus.PENDING,
                message("m-1", COLIN, "one"),
                message("m-2", CALLER, "two"),
                message("m-3", COLIN, "three"));

        TrillianConversationService.Reply reply = service.read(TENANT, CALLER, "thread-1", "m-1", 0);

        assertThat(reply.messages())
                .extracting(TrillianConversationService.Contribution::id)
                .containsExactly("m-2", "m-3");
    }

    @Test
    void readWaitsForSomethingNew_andSaysSoWhenNothingCame() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.PENDING);

        TrillianConversationService.Reply reply = service.read(TENANT, CALLER, "thread-1", null, 0);

        assertThat(reply.arrived()).isFalse();
        assertThat(reply.messages()).isEmpty();
    }

    @Test
    void readOnAHumanThreadIsNotFound() {
        MaximegalonDocument humanThread = conversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        humanThread.setAssignedToUserId(OTHER);
        when(threads.findById(TENANT, "thread-1")).thenReturn(Optional.of(humanThread));

        assertThatThrownBy(() -> service.read(TENANT, CALLER, "thread-1", null, 0))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation not found");
    }

    @Test
    void readFromAnUnknownCursor_readsFromTheStart() {
        givenConversation(
                "thread-1",
                CALLER,
                MaximegalonStatus.PENDING,
                message("m-1", COLIN, "one"),
                message("m-2", CALLER, "two"));

        TrillianConversationService.Reply reply = service.read(TENANT, CALLER, "thread-1", "m-gone", 0);

        assertThat(reply.messages())
                .extracting(TrillianConversationService.Contribution::id)
                .containsExactly("m-1", "m-2");
    }

    @Test
    void theTrilliansContributions_carryItsExposedName_notItsServiceAccount() {
        givenConversation(
                "thread-1",
                CALLER,
                MaximegalonStatus.PENDING,
                message("m-1", COLIN, "hi"),
                message("m-2", CALLER, "yo"));

        TrillianConversationService.Reply reply = service.read(TENANT, CALLER, "thread-1", null, 0);

        assertThat(reply.messages())
                .extracting(TrillianConversationService.Contribution::author)
                .containsExactly("colin", CALLER);
    }

    @Test
    void readOnAnEndedConversation_answersAtOnceAndSaysClosed() {
        givenConversation("thread-1", CALLER, MaximegalonStatus.ARCHIVED, message("m-1", COLIN, "bye"));

        long started = System.currentTimeMillis();
        TrillianConversationService.Reply reply = service.read(TENANT, CALLER, "thread-1", "m-1", 10_000);

        assertThat(reply.closed()).isTrue();
        assertThat(reply.arrived()).isFalse();
        assertThat(System.currentTimeMillis() - started).isLessThan(1_000);
    }

    // ──────────────────── bounds ────────────────────

    @Test
    void anOversizedContribution_isRefusedBeforeAnythingHappens() {
        String tooLong = "x".repeat(InboxMessagePostRequest.MAX_BODY_CHARS + 1);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, tooLong))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("contribution too long");
        assertThatThrownBy(() -> service.send(TENANT, CALLER, "thread-1", tooLong))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("contribution too long");
        verify(threads, never()).create(any());
        verify(threads, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void anOversizedTitle_isRefused() {
        String tooLong = "t".repeat(TrillianConversationService.MAX_TITLE_CHARS + 1);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", tooLong, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("title too long");
        verify(threads, never()).create(any());
    }

    @Test
    void oneCallerHoldsAtMostTheHeadLimitOfOpenConversations() {
        givenExposure(colinConfig("road.runner"), true);
        when(headService.hasHeadCapacity(any(), any())).thenReturn(true);
        when(threads.countOpenDiscussions(TENANT, CALLER, COLIN)).thenReturn(5L);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation limit reached");
        verify(threads, never()).create(any());
    }

    @Test
    void theConfiguredHeadLimit_lowersThePerCallerBound() {
        givenExposure(colinConfig("road.runner"), true);
        when(headService.hasHeadCapacity(any(), any())).thenReturn(true);
        when(exposureService.headLimitOf(TENANT, COLIN)).thenReturn(2);
        when(threads.countOpenDiscussions(TENANT, CALLER, COLIN)).thenReturn(2L);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation limit reached");
    }

    @Test
    void aSingleHeadedAgent_boundsTheOpenConversationsPerCaller_too() {
        givenExposure(colinConfig("road.runner"), true);
        TrillianNature singleHead = mock(TrillianNature.class);
        when(singleHead.multiHead()).thenReturn(false);
        when(natureRegistry.resolve(anyString())).thenReturn(singleHead);
        when(threads.countOpenDiscussions(eq(TENANT), eq(CALLER), eq(COLIN)))
                .thenReturn((long) TrillianConversationService.SINGLE_HEAD_CONVERSATION_LIMIT);

        assertThatThrownBy(() -> service.open(TENANT, CALLER, "colin", null, "Hi"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("conversation limit reached");
        verify(threads, never()).create(any());
    }

    // ──────────────────── waiting ────────────────────

    @Test
    void aWaitingRead_isWokenByTheMessageEvent_notByTheFallbackPoll() throws Exception {
        MaximegalonDocument quiet = conversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        AtomicReference<MaximegalonDocument> current = new AtomicReference<>(quiet);
        when(threads.findById(TENANT, "thread-1")).thenAnswer(inv -> Optional.of(current.get()));

        long started = System.currentTimeMillis();
        CompletableFuture<TrillianConversationService.Reply> pending =
                CompletableFuture.supplyAsync(() -> service.read(TENANT, CALLER, "thread-1", null, 10_000));
        awaitWaiting(1);

        MaximegalonMessage answer = message("m-1", COLIN, "here I am");
        MaximegalonDocument answered = conversation("thread-1", CALLER, MaximegalonStatus.PENDING, answer);
        current.set(answered);
        service.onThreadMessage(new MaximegalonMessageEvent(answered, answer));

        TrillianConversationService.Reply reply = pending.get(5, TimeUnit.SECONDS);
        assertThat(reply.arrived()).isTrue();
        assertThat(reply.messages())
                .extracting(TrillianConversationService.Contribution::id)
                .containsExactly("m-1");
        assertThat(System.currentTimeMillis() - started)
                .isLessThan(TrillianConversationService.FALLBACK_POLL_MS + 1_000);
        assertThat(service.waitingReadsOf(TENANT, CALLER)).isZero();
    }

    @Test
    void aConversationThatEndsWhileARead_waits_endsTheWait() throws Exception {
        MaximegalonDocument quiet = conversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        AtomicReference<MaximegalonDocument> current = new AtomicReference<>(quiet);
        when(threads.findById(TENANT, "thread-1")).thenAnswer(inv -> Optional.of(current.get()));

        long started = System.currentTimeMillis();
        CompletableFuture<TrillianConversationService.Reply> pending =
                CompletableFuture.supplyAsync(() -> service.read(TENANT, CALLER, "thread-1", null, 10_000));
        awaitWaiting(1);

        // Archived without a further message (idle sweep): the archive
        // event wakes the read, no fallback poll needed.
        MaximegalonDocument archived = conversation("thread-1", CALLER, MaximegalonStatus.ARCHIVED);
        current.set(archived);
        service.onThreadArchived(new MaximegalonArchivedEvent(archived));

        TrillianConversationService.Reply reply = pending.get(5, TimeUnit.SECONDS);
        assertThat(reply.closed()).isTrue();
        assertThat(reply.arrived()).isFalse();
        assertThat(System.currentTimeMillis() - started)
                .isLessThan(TrillianConversationService.FALLBACK_POLL_MS + 1_000);
    }

    @Test
    void theWaitingBound_isPerTenant() throws Exception {
        MaximegalonDocument quiet = conversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        AtomicReference<MaximegalonDocument> current = new AtomicReference<>(quiet);
        when(threads.findById(TENANT, "thread-1")).thenAnswer(inv -> Optional.of(current.get()));

        CompletableFuture<TrillianConversationService.Reply> pending =
                CompletableFuture.supplyAsync(() -> service.read(TENANT, CALLER, "thread-1", null, 10_000));
        awaitWaiting(1);

        assertThat(service.waitingReadsOf("other-tenant", CALLER)).isZero();

        MaximegalonMessage answer = message("m-1", COLIN, "done");
        MaximegalonDocument answered = conversation("thread-1", CALLER, MaximegalonStatus.PENDING, answer);
        current.set(answered);
        service.onThreadMessage(new MaximegalonMessageEvent(answered, answer));
        assertThat(pending.get(5, TimeUnit.SECONDS).arrived()).isTrue();
    }

    @Test
    void aCallerAtTheWaitingBound_isAnsweredAtOnce() throws Exception {
        MaximegalonDocument quiet = conversation("thread-1", CALLER, MaximegalonStatus.PENDING);
        AtomicReference<MaximegalonDocument> current = new AtomicReference<>(quiet);
        when(threads.findById(TENANT, "thread-1")).thenAnswer(inv -> Optional.of(current.get()));
        ExecutorService pool = Executors.newFixedThreadPool(TrillianConversationService.MAX_WAITING_PER_CALLER);
        List<CompletableFuture<TrillianConversationService.Reply>> parked = new ArrayList<>();
        for (int i = 0; i < TrillianConversationService.MAX_WAITING_PER_CALLER; i++) {
            parked.add(
                    CompletableFuture.supplyAsync(() -> service.read(TENANT, CALLER, "thread-1", null, 10_000), pool));
        }
        awaitWaiting(TrillianConversationService.MAX_WAITING_PER_CALLER);

        long started = System.currentTimeMillis();
        TrillianConversationService.Reply extra = service.read(TENANT, CALLER, "thread-1", null, 10_000);

        assertThat(extra.arrived()).isFalse();
        assertThat(System.currentTimeMillis() - started).isLessThan(1_000);

        MaximegalonMessage answer = message("m-1", COLIN, "done");
        MaximegalonDocument answered = conversation("thread-1", CALLER, MaximegalonStatus.PENDING, answer);
        current.set(answered);
        service.onThreadMessage(new MaximegalonMessageEvent(answered, answer));
        for (CompletableFuture<TrillianConversationService.Reply> read : parked) {
            assertThat(read.get(5, TimeUnit.SECONDS).arrived()).isTrue();
        }
        assertThat(service.waitingReadsOf(TENANT, CALLER)).isZero();
        pool.shutdown();
    }

    private void awaitWaiting(int expected) throws InterruptedException {
        long until = System.currentTimeMillis() + 5_000;
        while (service.waitingReadsOf(TENANT, CALLER) < expected && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        assertThat(service.waitingReadsOf(TENANT, CALLER)).isEqualTo(expected);
    }

    // ──────────────────── fixtures ────────────────────

    private static TrillianExposureConfig colinConfig(String... allowed) {
        return new TrillianExposureConfig(
                "colin", "Colin", List.of(allowed), 0, 0, new TrillianExposureConfig.Doors(true, false));
    }

    private void givenExposure(TrillianExposureConfig config, boolean mcpDoor) {
        TrillianExposureConfig effective = new TrillianExposureConfig(
                config.exposedName(),
                config.description(),
                config.allowedCounterparts(),
                config.headLimit(),
                config.idleMinutes(),
                new TrillianExposureConfig.Doors(mcpDoor, false));
        when(exposureService.findByExposedName(TENANT, "colin"))
                .thenReturn(Optional.of(new TrillianExposureService.Exposed(COLIN, effective)));
    }

    private void givenConversation(
            String id, String originator, MaximegalonStatus status, MaximegalonMessage... messages) {
        MaximegalonDocument item = conversation(id, originator, status, messages);
        when(threads.findById(TENANT, id)).thenReturn(Optional.of(item));
    }

    private static MaximegalonDocument conversation(
            String id, String originator, MaximegalonStatus status, MaximegalonMessage... messages) {
        MaximegalonDocument item = new MaximegalonDocument();
        item.setId(id);
        item.setTenantId(TENANT);
        item.setAssignedToUserId(COLIN);
        item.setOriginatorUserId(originator);
        item.setStatus(status);
        item.setType(MaximegalonType.OUTPUT_TEXT);
        item.setTags(new ArrayList<>(List.of(MaximegalonService.TAG_DISCUSSION)));
        item.setMessages(new ArrayList<>(List.of(messages)));
        return item;
    }

    private static Optional<MaximegalonService.Posted> posted(MaximegalonDocument item, MaximegalonMessage written) {
        return Optional.of(new MaximegalonService.Posted(item, written));
    }

    private static MaximegalonMessage message(String id, String author, String body) {
        return MaximegalonMessage.builder()
                .id(id)
                .authorUserId(author)
                .body(body)
                .createdAt(Instant.now())
                .build();
    }
}
