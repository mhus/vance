package de.mhus.vance.brain.trillian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.api.thinkprocess.PromptMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.brain.recipe.AppliedRecipe;
import de.mhus.vance.brain.recipe.RecipeResolver;
import de.mhus.vance.brain.recipe.RecipeSource;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.brain.trillian.exposure.TrillianExposureService;
import de.mhus.vance.brain.trillian.nature.TrillianNature;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonMessage;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The head lifecycle: spawn (wired to its loop, bounded by the limit,
 * rebuilt under a fresh name, race-safe), the sweep's mechanical ends, the
 * headless conversations nothing else answers, and the conclusion contract —
 * {@link TrillianNature#headConcluded} once per conversation, with the real
 * cause.
 */
class TrillianHeadServiceTest {

    private static final String TENANT = "acme";
    private static final String ACCOUNT = "_trillian-colin-1234";
    private static final String THREAD = "thread-1";

    // Sweep windows short enough that the tests state their case in hours
    // rather than days.
    private static final long REMINDER_MS = 60_000;
    private static final long FALLBACK_MS = 120_000;
    private static final long INACTIVITY_MS = 600_000;

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ThinkEngineService thinkEngineService = mock(ThinkEngineService.class);
    private final RecipeResolver recipeResolver = mock(RecipeResolver.class);
    private final EngineMessageRouter router = mock(EngineMessageRouter.class);
    private final MaximegalonService threads = mock(MaximegalonService.class);
    private final TrillianNatureRegistry natureRegistry = mock(TrillianNatureRegistry.class);
    private final TrillianModelGate modelGate = mock(TrillianModelGate.class);
    private final TrillianInternalApi trillianApi = mock(TrillianInternalApi.class);
    private final TrillianExposureService exposureService = mock(TrillianExposureService.class);

    private final TrillianNature nature = mock(TrillianNature.class);
    private final TrillianHeadService service = new TrillianHeadService(
            thinkProcessService,
            thinkEngineService,
            recipeResolver,
            router,
            threads,
            natureRegistry,
            modelGate,
            trillianApi,
            exposureService,
            REMINDER_MS,
            FALLBACK_MS,
            INACTIVITY_MS);

    private final ThinkProcessDocument loop = loop();

    /** Stands in for the live processes of the loop's session, mutations included. */
    private final List<ThinkProcessDocument> sessionProcesses = new ArrayList<>(List.of(loop));

    @BeforeEach
    void setUp() {
        when(nature.id()).thenReturn("colin");
        when(nature.headLimit()).thenReturn(2);
        when(nature.headRecipe()).thenReturn("trillian-head-colin");
        when(natureRegistry.resolve(any())).thenReturn(nature);
        when(router.dispatch(any(), anyString(), any())).thenReturn(true);
        when(thinkProcessService.findBySession(TENANT, "session-1")).thenReturn(sessionProcesses);
        // The sweep's marker writes land in Mongo and are re-read with the next
        // liveHeads() call; the test's in-memory heads stand in for that
        // re-read, so the mocked write has to actually mutate them.
        org.mockito.Mockito.doAnswer(inv -> {
                    String processId = inv.getArgument(0);
                    String key = inv.getArgument(1);
                    Object value = inv.getArgument(2);
                    for (ThinkProcessDocument proc : sessionProcesses) {
                        if (processId.equals(proc.getId()) && proc.getEngineParamOverrides() != null) {
                            if (value == null) {
                                proc.getEngineParamOverrides().remove(key);
                            } else {
                                proc.getEngineParamOverrides().put(key, value);
                            }
                        }
                    }
                    return true;
                })
                .when(thinkProcessService)
                .setEngineParamOverride(any(), any(), any());
    }

    // ──────────────────── Spawning ────────────────────

    @Test
    void aFreshHead_isSpawnedIdle_wiredToItsLoopAndThread() {
        givenHeadRecipe();
        givenNoHeads();
        ThinkProcessDocument created = head("head-1");
        when(thinkProcessService.create(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(created);

        Optional<ThinkProcessDocument> head = service.ensureHead(loop, nature, THREAD);

        assertThat(head).contains(created);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(thinkProcessService)
                .create(
                        eq(TENANT),
                        eq(loop.getProjectId()),
                        eq(loop.getSessionId()),
                        eq("trillian-head-" + THREAD),
                        eq("trillian-head"),
                        any(),
                        any(),
                        any(),
                        any(),
                        params.capture(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        assertThat(params.getValue())
                .containsEntry(TrillianHeadService.PARAM_HEAD_THREAD, THREAD)
                .containsEntry(TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID, "loop-1")
                .containsEntry(TrillianSessionBootstrapper.PARAM_NATURE, "colin")
                .containsEntry(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, ACCOUNT);
        // Idle, not started: no greeting turn — the contribution is the first.
        verify(thinkProcessService).updateStatus("head-1", ThinkProcessStatus.IDLE);
    }

    @Test
    void anExistingLiveHead_isReused_notDuplicated() {
        ThinkProcessDocument existing = head("head-1");
        sessionProcesses.add(existing);

        Optional<ThinkProcessDocument> head = service.ensureHead(loop, nature, THREAD);

        assertThat(head).contains(existing);
        verify(thinkProcessService, never())
                .create(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void aHeadAtTheLimit_isRefused() {
        // Limit 2, both slots taken by other threads.
        sessionProcesses.addAll(List.of(headFor("thread-2"), headFor("thread-3")));

        Optional<ThinkProcessDocument> head = service.ensureHead(loop, nature, THREAD);

        assertThat(head).isEmpty();
        verify(thinkProcessService, never())
                .create(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void theExposureConfig_lowersTheHeadLimit() {
        when(exposureService.headLimitOf(TENANT, ACCOUNT)).thenReturn(1);
        sessionProcesses.add(headFor("thread-2"));

        assertThat(service.hasHeadCapacity(loop, nature)).isFalse();
    }

    @Test
    void theExposureConfig_cannotLiftTheNaturesHeadLimit() {
        // The Nature's limit is the bound on cost and flood abuse; a hub
        // document must not lift it.
        when(exposureService.headLimitOf(TENANT, ACCOUNT)).thenReturn(50);
        sessionProcesses.addAll(List.of(headFor("thread-2"), headFor("thread-3")));

        assertThat(service.hasHeadCapacity(loop, nature)).isFalse();
    }

    @Test
    void aClosedHead_isNotTheLimit_andIsRebuilt() {
        when(nature.headLimit()).thenReturn(1);
        ThinkProcessDocument closed = headFor("thread-2");
        closed.setStatus(ThinkProcessStatus.CLOSED);
        sessionProcesses.add(closed);
        givenHeadRecipe();
        ThinkProcessDocument created = head("head-2");
        when(thinkProcessService.create(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(created);

        Optional<ThinkProcessDocument> head = service.ensureHead(loop, nature, THREAD);

        assertThat(head).contains(created);
    }

    @Test
    void aThreadWhoseHeadWasClosed_getsANewHead_underTheNextFreeName() {
        // Names are unique per session even for CLOSED processes: reusing
        // trillian-head-<thread> would collide forever and the thread would
        // never be answered again.
        ThinkProcessDocument closed = headFor(THREAD);
        closed.setStatus(ThinkProcessStatus.CLOSED);
        sessionProcesses.add(closed);
        givenHeadRecipe();
        ThinkProcessDocument created = head("head-2");
        when(thinkProcessService.create(
                        anyString(),
                        any(),
                        any(),
                        eq("trillian-head-" + THREAD + "-2"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(created);

        assertThat(service.ensureHead(loop, nature, THREAD)).contains(created);
    }

    @Test
    void aRaceLostOnTheName_adoptsTheWinnersHead() {
        givenHeadRecipe();
        ThinkProcessDocument winner = head("head-other-pod");
        when(thinkProcessService.create(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenAnswer(inv -> {
                    // The other pod's insert landed first.
                    sessionProcesses.add(winner);
                    throw new org.springframework.dao.DuplicateKeyException("tenant_session_name_idx");
                });

        assertThat(service.ensureHead(loop, nature, THREAD)).contains(winner);
    }

    @Test
    void aHeadRefusedByTheModelGate_isNotSpawned() {
        givenHeadRecipe();
        givenNoHeads();
        org.mockito.Mockito.doThrow(new IllegalStateException("model not approved"))
                .when(modelGate)
                .checkLoopModel(any(), any(), any(), any(), any());

        assertThat(service.ensureHead(loop, nature, THREAD)).isEmpty();
        verify(thinkProcessService, never())
                .create(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
    }

    // ──────────────────── Closing ────────────────────

    @Test
    void aHeadWhoseThreadIsArchived_isClosedOnce_withItsConclusion() {
        ThinkProcessDocument head = headFor(THREAD);
        sessionProcesses.add(head);
        when(threads.findById(TENANT, THREAD)).thenReturn(Optional.of(archivedConversation()));

        service.sweepLoop(loop, Instant.now());

        verify(thinkProcessService).closeProcess("head-1", de.mhus.vance.api.thinkprocess.CloseReason.DONE);
        verify(natureRegistry.resolve(any()))
                .headConcluded(TENANT, ACCOUNT, THREAD, TrillianNature.HeadConclusion.COMPLETED);
    }

    @Test
    void anAlreadyClosedHead_isNotClosedAgain() {
        ThinkProcessDocument head = headFor(THREAD);
        head.setStatus(ThinkProcessStatus.CLOSED);
        sessionProcesses.add(head);
        when(threads.findById(TENANT, THREAD)).thenReturn(Optional.of(archivedConversation()));

        service.sweepLoop(loop, Instant.now());

        verify(thinkProcessService, never()).closeProcess(any(), any());
        verify(natureRegistry.resolve(any()), never()).headConcluded(any(), any(), any(), any());
    }

    // ──────────────────── Sweep: the mechanical ends ────────────────────

    @Test
    void anInactiveConversation_isClosedWithAFarewell() {
        givenOpenThread(
                conversationWithMessages(message("m-1", "bob", Instant.now().minus(2, ChronoUnit.HOURS))));

        service.sweepLoop(loop, Instant.now());

        verify(threads).postMessage(eq(TENANT), eq(THREAD), eq(ACCOUNT), any(), any());
        verify(threads).archive(TENANT, THREAD, ACCOUNT);
        // Headless: no closing pass will follow, so the cause is reported now.
        verify(nature).headConcluded(TENANT, ACCOUNT, THREAD, TrillianNature.HeadConclusion.INACTIVITY);
    }

    @Test
    void anInactiveConversationWithAHead_reportsInactivity_whenTheHeadCloses() {
        givenOpenThread(
                conversationWithMessages(message("m-1", "bob", Instant.now().minus(2, ChronoUnit.HOURS))));
        ThinkProcessDocument head = headFor(THREAD);
        sessionProcesses.add(head);
        when(threads.findById(TENANT, THREAD)).thenReturn(Optional.of(conversationWithMessages()));

        service.sweepLoop(loop, Instant.now());

        verify(threads).archive(TENANT, THREAD, ACCOUNT);
        verify(nature, never()).headConcluded(any(), any(), any(), any());

        // Next round: the thread is archived, the closing pass reads the cause.
        when(threads.findById(TENANT, THREAD)).thenReturn(Optional.of(archivedConversation()));
        when(threads.listForUser(TENANT, ACCOUNT, null)).thenReturn(List.of());
        service.sweepLoop(loop, Instant.now());

        verify(thinkProcessService).closeProcess("head-1", de.mhus.vance.api.thinkprocess.CloseReason.DONE);
        verify(nature).headConcluded(TENANT, ACCOUNT, THREAD, TrillianNature.HeadConclusion.INACTIVITY);
        verify(nature, org.mockito.Mockito.times(1)).headConcluded(any(), any(), any(), any());
    }

    @Test
    void aClosingHead_prunesTheTaskAddressesThatPointAtIt() {
        sessionProcesses.add(headFor(THREAD));
        when(threads.findById(TENANT, THREAD)).thenReturn(Optional.of(archivedConversation()));

        service.sweepLoop(loop, Instant.now());

        verify(trillianApi).pruneRepliesTo("loop-1", "head-1");
    }

    // ──────────────────── Sweep: conversations without a head ────────────────────

    @Test
    void aHeadlessUnansweredContribution_getsAHeadOnceASlotIsFree() {
        // Refused at the limit when it arrived; now a slot is free.
        givenOpenThread(
                conversationWithMessages(message("m-1", "bob", Instant.now().minus(30, ChronoUnit.SECONDS))));
        givenHeadRecipe();
        ThinkProcessDocument created = head("head-9");
        when(thinkProcessService.create(
                        anyString(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(created);

        service.sweepLoop(loop, Instant.now());

        verify(router).dispatch(any(), eq("head-9"), any());
        verify(threads, never()).postMessage(any(), any(), any(), any(), any());
    }

    @Test
    void aHeadlessUnansweredContribution_pastTheReminderWindow_isDeclinedInTheThread() {
        // Still no slot: the conversation is told so, instead of waiting on silence.
        when(nature.headLimit()).thenReturn(0);
        givenOpenThread(
                conversationWithMessages(message("m-1", "bob", Instant.now().minus(5, ChronoUnit.MINUTES))));

        service.sweepLoop(loop, Instant.now());

        verify(threads).postMessage(eq(TENANT), eq(THREAD), eq(ACCOUNT), any(), any());
        verify(threads, never()).archive(any(), any(), any());
    }

    @Test
    void aBodyOnlyOpener_nobodyAnswered_isOwedLikeAnyContribution() {
        // Opened over the door while the head limit was full: no messages,
        // the body is the contribution — it must not rest forever.
        when(nature.headLimit()).thenReturn(0);
        MaximegalonDocument opened = conversationWithMessages();
        opened.setBody("Hello, anyone there?");
        opened.setCreatedAt(Instant.now().minus(6, ChronoUnit.MINUTES));
        givenOpenThread(opened);

        service.sweepLoop(loop, Instant.now());

        verify(threads).postMessage(eq(TENANT), eq(THREAD), eq(ACCOUNT), any(), any());
    }

    @Test
    void aNoticeOnTheTrilliansDesk_isNoConversation_andIsLeftAlone() {
        // A share notice with an old comment: not opened to be answered —
        // no head, no decline, no inactivity close.
        MaximegalonDocument notice =
                conversationWithMessages(message("m-1", "bob", Instant.now().minus(30, ChronoUnit.DAYS)));
        notice.setTags(new ArrayList<>());
        notice.setBody("Bob shared 'notes/plan.md' with you.");
        givenOpenThread(notice);

        service.sweepLoop(loop, Instant.now());

        verify(threads, never()).postMessage(any(), any(), any(), any(), any());
        verify(threads, never()).archive(any(), any(), any());
        verify(router, never()).dispatch(any(), anyString(), any());
    }

    @Test
    void aHeadlessFreshContribution_isNotDeclinedYet() {
        when(nature.headLimit()).thenReturn(0);
        givenOpenThread(
                conversationWithMessages(message("m-1", "bob", Instant.now().minus(10, ChronoUnit.SECONDS))));

        service.sweepLoop(loop, Instant.now());

        verify(threads, never()).postMessage(any(), any(), any(), any(), any());
    }

    @Test
    void anUnansweredContribution_pastTheReminderWindow_getsAReminderOnce() {
        givenOpenThread(
                conversationWithMessages(message("m-1", "bob", Instant.now().minus(5, ChronoUnit.MINUTES))));
        ThinkProcessDocument head = headFor(THREAD);
        sessionProcesses.add(head);

        service.sweepLoop(loop, Instant.now());

        // One marker per contribution: the second round nags no more.
        service.sweepLoop(loop, Instant.now());

        verify(router, org.mockito.Mockito.times(1)).dispatch(any(), eq("head-1"), any());
        verify(threads, never()).archive(any(), any(), any());
    }

    @Test
    void anUnansweredContribution_pastTheFallbackWindow_getsTheMechanicalAnswer() {
        givenOpenThread(
                conversationWithMessages(message("m-1", "bob", Instant.now().minus(3, ChronoUnit.MINUTES))));
        ThinkProcessDocument head = headFor(THREAD);
        sessionProcesses.add(head);

        service.sweepLoop(loop, Instant.now());
        service.sweepLoop(loop, Instant.now().plus(2, ChronoUnit.MINUTES));

        // The fallback answers as the account — and the conversation stays
        // open: the counterpart can simply write again.
        verify(threads).postMessage(eq(TENANT), eq(THREAD), eq(ACCOUNT), any(), any());
        verify(threads, never()).archive(any(), any(), any());
    }

    @Test
    void anAnsweredConversation_owesNothing() {
        givenOpenThread(conversationWithMessages(
                message("m-1", "bob", Instant.now().minus(10, ChronoUnit.MINUTES)),
                message("m-2", ACCOUNT, Instant.now().minus(9, ChronoUnit.MINUTES))));
        ThinkProcessDocument head = headFor(THREAD);
        sessionProcesses.add(head);

        service.sweepLoop(loop, Instant.now());

        verify(router, never()).dispatch(any(), any(), any());
        verify(threads, never()).postMessage(any(), any(), any(), any(), any());
    }

    @Test
    void aConversationNearTheMessageWall_isClosedMechanicallyBeforeTheWall() {
        MaximegalonMessage[] many = new MaximegalonMessage[TrillianHeadService.LIMIT_CLOSE];
        for (int i = 0; i < many.length; i++) {
            many[i] = message("m-" + i, i % 2 == 0 ? "bob" : ACCOUNT, Instant.now());
        }
        givenOpenThread(conversationWithMessages(many));

        service.sweepLoop(loop, Instant.now());

        verify(threads).postMessage(eq(TENANT), eq(THREAD), eq(ACCOUNT), any(), any());
        verify(threads).archive(TENANT, THREAD, ACCOUNT);
    }

    @Test
    void aConversationNearTheNudge_getsOneConcludeNudge() {
        MaximegalonMessage[] many = new MaximegalonMessage[TrillianHeadService.LIMIT_NUDGE];
        for (int i = 0; i < many.length; i++) {
            many[i] = message("m-" + i, i % 2 == 0 ? "bob" : ACCOUNT, Instant.now());
        }
        givenOpenThread(conversationWithMessages(many));
        ThinkProcessDocument head = headFor(THREAD);
        sessionProcesses.add(head);

        service.sweepLoop(loop, Instant.now());
        service.sweepLoop(loop, Instant.now());

        verify(router, org.mockito.Mockito.times(1)).dispatch(any(), eq("head-1"), any());
        verify(threads, never()).archive(any(), any(), any());
    }

    @Test
    void anAccountEnd_concludesEveryOpenConversation() {
        MaximegalonDocument first = conversationWithMessages(message("m-1", "bob", Instant.now()));
        MaximegalonDocument second = conversationWithMessages(message("m-1", "bob", Instant.now()));
        second.setId("thread-2");
        when(threads.listForUser(TENANT, ACCOUNT, null)).thenReturn(List.of(first, second));

        List<String> concluded = service.concludeOpenThreads(TENANT, ACCOUNT, nature);

        assertThat(concluded).containsExactlyInAnyOrder(THREAD, "thread-2");
        verify(threads, org.mockito.Mockito.times(2)).archive(any(), any(), eq(ACCOUNT));
        // The heads went with the loop session's cascade, without a closing
        // pass — the account end itself reports each conversation, once.
        verify(nature).headConcluded(TENANT, ACCOUNT, THREAD, TrillianNature.HeadConclusion.ACCOUNT_ENDED);
        verify(nature).headConcluded(TENANT, ACCOUNT, "thread-2", TrillianNature.HeadConclusion.ACCOUNT_ENDED);
    }

    // ──────────────────── Fixtures ────────────────────

    private static ThinkProcessDocument loop() {
        ThinkProcessDocument loop = new ThinkProcessDocument();
        loop.setId("loop-1");
        loop.setTenantId(TENANT);
        loop.setSessionId("session-1");
        loop.setProjectId("_user__trillian-colin-1234");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianSessionBootstrapper.PARAM_NATURE, "colin");
        params.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, ACCOUNT);
        loop.setEngineParams(params);
        return loop;
    }

    private static ThinkProcessDocument head(String id) {
        return headFor(THREAD, id);
    }

    private static ThinkProcessDocument headFor(String threadId) {
        return headFor(threadId, "head-1");
    }

    private static ThinkProcessDocument headFor(String threadId, String id) {
        ThinkProcessDocument head = new ThinkProcessDocument();
        head.setId(id);
        head.setTenantId(TENANT);
        head.setSessionId("session-1");
        head.setProjectId("_user__trillian-colin-1234");
        head.setName("trillian-head-" + threadId);
        head.setStatus(ThinkProcessStatus.IDLE);
        head.setCreatedAt(Instant.now());
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianHeadService.PARAM_HEAD_THREAD, threadId);
        head.setEngineParams(params);
        head.setEngineParamOverrides(new LinkedHashMap<>());
        return head;
    }

    private void givenNoHeads() {
        sessionProcesses.clear();
        sessionProcesses.add(loop);
    }

    private void givenHeadRecipe() {
        AppliedRecipe applied = new AppliedRecipe(
                "trillian-head-colin",
                "trillian-head",
                Map.of(),
                null,
                null,
                PromptMode.APPEND,
                null,
                null,
                null,
                List.of(),
                null,
                RecipeSource.RESOURCE,
                List.of(),
                null);
        when(recipeResolver.applyDefaulting(any(), any(), any(), any(), any())).thenReturn(applied);
        ThinkEngine engine = mock(ThinkEngine.class);
        when(engine.name()).thenReturn("trillian-head");
        when(engine.version()).thenReturn("0.1.0");
        when(thinkEngineService.resolve("trillian-head")).thenReturn(Optional.of(engine));
    }

    private void givenOpenThread(MaximegalonDocument item) {
        when(threads.listForUser(TENANT, ACCOUNT, null)).thenReturn(List.of(item));
    }

    private static MaximegalonDocument archivedConversation() {
        MaximegalonDocument item = conversationWithMessages();
        item.setStatus(MaximegalonStatus.ARCHIVED);
        return item;
    }

    private static MaximegalonDocument conversationWithMessages(MaximegalonMessage... messages) {
        MaximegalonDocument item = new MaximegalonDocument();
        item.setId(THREAD);
        item.setTenantId(TENANT);
        item.setAssignedToUserId(ACCOUNT);
        item.setOriginatorUserId("bob");
        item.setType(MaximegalonType.OUTPUT_TEXT);
        item.setStatus(MaximegalonStatus.PENDING);
        item.setCreatedAt(Instant.now());
        item.setTags(new ArrayList<>(List.of(MaximegalonService.TAG_DISCUSSION)));
        item.setMessages(new ArrayList<>(List.of(messages)));
        return item;
    }

    private static MaximegalonMessage message(String id, String author, Instant at) {
        return MaximegalonMessage.builder()
                .id(id)
                .authorUserId(author)
                .body("body of " + id)
                .createdAt(at)
                .readBy(new ArrayList<>())
                .reactions(new ArrayList<>())
                .build();
    }
}
