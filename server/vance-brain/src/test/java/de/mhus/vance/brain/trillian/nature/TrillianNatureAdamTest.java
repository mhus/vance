package de.mhus.vance.brain.trillian.nature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.trillian.TrillianAttributeStore;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What separates adam from Nature void is durability, so that is what these
 * check: the map goes to the store, comes back from it, and Nature void
 * still does neither.
 */
@ExtendWith(MockitoExtension.class)
class TrillianNatureAdamTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "test1";
    private static final String ACCOUNT = "_trillian-adam-4711";

    @Mock
    ThinkProcessService thinkProcessService;
    @Mock
    TrillianAttributeStore attributeStore;
    @Mock
    de.mhus.vance.brain.trillian.TrillianJournalStore journalStore;
    @Mock
    de.mhus.vance.brain.ai.light.LightLlmService lightLlm;
    @Mock
    TrillianCharacterCatalog characterCatalog;

    @Test
    void aChangedMap_isMirroredToTheStore() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("persona", "witziger Schwabe");

        adam().attributesChanged(worker(ACCOUNT), attrs);

        verify(attributeStore).save(TENANT, PROJECT, ACCOUNT, attrs);
    }

    @Test
    void theCallName_isTheGivenName() {
        assertThat(adam().callName(Map.of("name", "Ada"))).isEqualTo("Ada");
    }

    @Test
    void withoutAName_theEngineNameStands() {
        // Nature void has no names at all, and an adam whose name the human
        // cleared should not announce itself as an empty string.
        assertThat(adam().callName(Map.of())).isEqualTo("Trillian");
        assertThat(adam().callName(Map.of("name", "  "))).isEqualTo("Trillian");
        assertThat(new TrillianNatureVoid(thinkProcessService).callName(Map.of("name", "Ada")))
                .isEqualTo("Trillian");
    }

    @Test
    void aBrandNewAccount_getsACharacter() {
        when(attributeStore.load(TENANT, PROJECT, ACCOUNT)).thenReturn(Map.of());
        when(characterCatalog.generate(eq(TENANT), eq(PROJECT), any()))
                .thenReturn(Map.of("name", "Ada", "gender", "female", "character", "Terse."));

        Map<String, Object> attrs = adam().initialAttributes(TENANT, PROJECT, ACCOUNT);

        // A worker called _trillian-adam-4711 is a process; one called
        // Ada is someone a human can talk about.
        assertThat(attrs).containsKeys("name", "gender", "character");
    }

    @Test
    void aGeneratedCharacter_isWrittenDownAtOnce() {
        // Otherwise the next boot generates a different name, and an
        // identity regenerated on restart is not an identity.
        when(attributeStore.load(TENANT, PROJECT, ACCOUNT)).thenReturn(Map.of());
        when(characterCatalog.generate(eq(TENANT), eq(PROJECT), any()))
                .thenReturn(Map.of("name", "Ada"));

        Map<String, Object> attrs = adam().initialAttributes(TENANT, PROJECT, ACCOUNT);

        verify(attributeStore).save(TENANT, PROJECT, ACCOUNT, attrs);
    }

    @Test
    void anAccountThatAlreadyHasAttributes_keepsThem() {
        // Including a name the human changed — a generated character is a
        // starting point, not a fact about the Trillian.
        when(attributeStore.load(TENANT, PROJECT, ACCOUNT))
                .thenReturn(Map.of("name", "Zaphod"));

        assertThat(adam().initialAttributes(TENANT, PROJECT, ACCOUNT))
                .containsEntry("name", "Zaphod");
        verify(attributeStore, never()).save(any(), any(), any(), any());
    }

    @Test
    void aFreshWorker_isSeededFromTheStore() {
        when(attributeStore.load(TENANT, PROJECT, ACCOUNT))
                .thenReturn(Map.of("language", "Deutsch"));

        assertThat(adam().initialAttributes(TENANT, PROJECT, ACCOUNT))
                .containsEntry("language", "Deutsch");
    }

    @Test
    void aWorkerWithoutAnAccount_isNotFiledAnywhere() {
        // Nothing to key the document on. Broken wiring, not a case to
        // invent a filename for.
        adam().attributesChanged(worker(null), Map.of("a", "b"));

        verify(attributeStore, never()).save(anyString(), anyString(), anyString(), any());
    }

    @Test
    void natureVoid_persistsNothing() {
        TrillianNatureVoid voidNature = new TrillianNatureVoid(thinkProcessService);

        voidNature.attributesChanged(worker(ACCOUNT), Map.of("a", "b"));

        // Nature void is ephemeral by definition; if this ever started
        // writing, the two generations would stop differing.
        assertThat(voidNature.initialAttributes(TENANT, PROJECT, ACCOUNT)).isEmpty();
        verify(attributeStore, never()).save(anyString(), anyString(), anyString(), any());
    }

    @Test
    void aDiscardedAccount_takesItsDocumentsWithIt() {
        adam().accountDiscarded(TENANT, PROJECT, ACCOUNT);

        verify(attributeStore).discard(TENANT, PROJECT, ACCOUNT);
        verify(journalStore).discard(TENANT, PROJECT, ACCOUNT);
    }

    @Test
    void aLessonWorthKeeping_reachesTheJournal() {
        givenReflexion(true, "- reports/ rejects writes from this account");

        adam().taskConcluded(worker(ACCOUNT), "task-1",
                TrillianNature.TaskOutcome.DONE, "listed 17 documents");

        verify(journalStore).append(TENANT, PROJECT, ACCOUNT,
                "- reports/ rejects writes from this account");
    }

    @Test
    void nothingWorthKeeping_writesNothing() {
        // Silence is the normal outcome. A journal of "task done" lines
        // costs context on every later turn and teaches nothing.
        givenReflexion(false, "");

        adam().taskConcluded(worker(ACCOUNT), "task-1",
                TrillianNature.TaskOutcome.DONE, "listed 17 documents");

        verify(journalStore, never()).append(any(), any(), any(), any());
    }

    @Test
    void aFailedTask_isReflectedOnToo() {
        // This is where reflexion earns its keep — a Trillian that only
        // reviews its successes learns nothing.
        givenReflexion(true, "- the export needs WRITER on the target project");

        adam().taskConcluded(worker(ACCOUNT), "task-2",
                TrillianNature.TaskOutcome.FAILED, "could not write the report");

        verify(journalStore).append(TENANT, PROJECT, ACCOUNT,
                "- the export needs WRITER on the target project");
    }

    @Test
    void aFailingReflexion_isSwallowed() {
        // It runs inside the tool call that reports the result. Losing a
        // lesson is acceptable; losing the task outcome is not.
        when(lightLlm.callForJson(any())).thenThrow(new IllegalStateException("model down"));

        adam().taskConcluded(worker(ACCOUNT), "task-3",
                TrillianNature.TaskOutcome.DONE, "done");

        verify(journalStore, never()).append(any(), any(), any(), any());
    }

    @Test
    void theJournalIsReadBackIntoThePrompt() {
        // Reflexion that never reaches a prompt is writing without a
        // reader.
        when(journalStore.tail(TENANT, PROJECT, ACCOUNT))
                .thenReturn("- reports/ is read-only for me");

        assertThat(adam().userPromptAddendum(worker(ACCOUNT)))
                .contains("reports/ is read-only for me");
    }

    @Test
    void anEmptyJournal_addsNoSection() {
        when(journalStore.tail(TENANT, PROJECT, ACCOUNT)).thenReturn(null);

        assertThat(adam().userPromptAddendum(worker(ACCOUNT)))
                .doesNotContain("What you learned earlier");
    }

    @Test
    void natureVoid_doesNotReflect() {
        TrillianNatureVoid voidNature = new TrillianNatureVoid(thinkProcessService);

        voidNature.taskConcluded(worker(ACCOUNT), "task-1",
                TrillianNature.TaskOutcome.DONE, "done");

        verify(lightLlm, never()).callForJson(any());
        verify(journalStore, never()).append(any(), any(), any(), any());
    }

    @Test
    void obsoleteEntries_arePrunedByPosition() {
        when(journalStore.entries(TENANT, PROJECT, ACCOUNT))
                .thenReturn(java.util.List.of("- one", "- two", "- three"));
        when(lightLlm.callForJson(any())).thenReturn(
                Map.of("keep", false, "entry", "", "remove", java.util.List.of(2)));

        adam().taskConcluded(worker(ACCOUNT), "task-1",
                TrillianNature.TaskOutcome.DONE, "done");

        verify(journalStore).removeEntries(TENANT, PROJECT, ACCOUNT, java.util.List.of(2));
    }

    @Test
    void pruningHappensBeforeAppending() {
        // Otherwise a position could point at the line this very
        // reflexion just added.
        when(journalStore.entries(TENANT, PROJECT, ACCOUNT))
                .thenReturn(java.util.List.of("- stale"));
        when(lightLlm.callForJson(any())).thenReturn(
                Map.of("keep", true, "entry", "- fresh", "remove", java.util.List.of(1)));

        adam().taskConcluded(worker(ACCOUNT), "task-1",
                TrillianNature.TaskOutcome.DONE, "done");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(journalStore);
        order.verify(journalStore).removeEntries(TENANT, PROJECT, ACCOUNT, java.util.List.of(1));
        order.verify(journalStore).append(TENANT, PROJECT, ACCOUNT, "- fresh");
    }

    @Test
    void aStrayPosition_costsOnlyThatPrune() {
        // The indices come from an LLM reading a numbered list. One bad
        // number must not take the reflexion down with it.
        when(journalStore.entries(TENANT, PROJECT, ACCOUNT))
                .thenReturn(java.util.List.of("- one"));
        when(lightLlm.callForJson(any())).thenReturn(Map.of(
                "keep", true, "entry", "- fresh",
                "remove", java.util.List.of(0, 7, "x")));

        adam().taskConcluded(worker(ACCOUNT), "task-1",
                TrillianNature.TaskOutcome.DONE, "done");

        verify(journalStore, never()).removeEntries(any(), any(), any(), any());
        verify(journalStore).append(TENANT, PROJECT, ACCOUNT, "- fresh");
    }

    @Test
    void aParkedWorker_isWorthWakingFor() {
        // It asked something and nothing will ever reach it on its own.
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(
                        childProcess("ask-worker", ThinkProcessStatus.IDLE)));

        java.util.List<SelfCheckFinding> findings = adam().selfCheckFindings(loopProcess());

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind()).isEqualTo(SelfCheckFinding.Kind.WORKER_WAITING);
    }

    @Test
    void aStateBlocker_getsAReCheckBeforeTheHumanIsDisturbed() {
        // A lock can be gone by now, and looking costs one worker turn
        // against a human's attention.
        ThinkProcessDocument parked = childProcess("ask-worker", ThinkProcessStatus.IDLE);
        parked.setEngineParamOverrides(new LinkedHashMap<>(Map.of(
                de.mhus.vance.brain.trillian.tools.TrillianAskTool.PARAM_ASK_BLOCKER, "state")));
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(parked));

        SelfCheckFinding finding = adam().selfCheckFindings(loopProcess()).get(0);

        assertThat(finding.detail()).contains("process_steer").contains("re-check");
    }

    @Test
    void aDecisionBlocker_goesStraightToTheHuman() {
        // Nothing about a pending choice changes by looking at it again.
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(
                        childProcess("ask-worker", ThinkProcessStatus.IDLE)));

        SelfCheckFinding finding = adam().selfCheckFindings(loopProcess()).get(0);

        // The wording covers two cases now — a decision, and a state
        // whose breaker has opened — so it asserts the behaviour rather
        // than the word: ask, do not look again.
        assertThat(finding.detail()).contains("Ask Control").doesNotContain("re-check");
    }

    @Test
    void aStateThatNeverChanges_becomesADecision() {
        // The circuit opens: after three rounds it has behaved like a
        // decision long enough to be treated as one.
        ThinkProcessDocument parked = childProcess("ask-worker", ThinkProcessStatus.IDLE);
        parked.setEngineParamOverrides(new LinkedHashMap<>(Map.of(
                de.mhus.vance.brain.trillian.tools.TrillianAskTool.PARAM_ASK_BLOCKER, "state",
                TrillianNatureAdam.PARAM_ASK_PROBES, TrillianNatureAdam.MAX_ASK_PROBES)));
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(parked));

        SelfCheckFinding finding = adam().selfCheckFindings(loopProcess()).get(0);

        assertThat(finding.detail()).doesNotContain("re-check").contains("Ask Control");
    }

    @Test
    void anOpenBreaker_reopensAfterTheCooldown() {
        // Half-open: a lock that survived three rounds can still be gone
        // by the afternoon, and never looking again would make "give up"
        // mean "give up permanently".
        ThinkProcessDocument parked = childProcess("ask-worker", ThinkProcessStatus.IDLE);
        parked.setEngineParamOverrides(new LinkedHashMap<>(Map.of(
                de.mhus.vance.brain.trillian.tools.TrillianAskTool.PARAM_ASK_BLOCKER, "state",
                TrillianNatureAdam.PARAM_ASK_PROBES, TrillianNatureAdam.MAX_ASK_PROBES,
                TrillianNatureAdam.PARAM_ASK_OPENED_AT,
                java.time.Instant.now()
                        .minus(TrillianNatureAdam.ASK_PROBE_COOLDOWN)
                        .minusSeconds(60).toEpochMilli())));
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(parked));
        when(thinkProcessService.findById("child-ask-worker"))
                .thenReturn(java.util.Optional.of(parked));

        TrillianNatureAdam adam = adam();
        java.util.List<SelfCheckFinding> findings = adam.selfCheckFindings(loopProcess());
        adam.selfCheckDelivered(loopProcess(), findings);

        assertThat(findings.get(0).detail()).contains("re-check");
        // And the cool-down restarts, so it is one trial and not a new
        // round of three.
        verify(thinkProcessService).setEngineParamOverride(
                org.mockito.ArgumentMatchers.eq("child-ask-worker"),
                org.mockito.ArgumentMatchers.eq(TrillianNatureAdam.PARAM_ASK_OPENED_AT),
                any());
    }

    @Test
    void gathering_spendsNothingUntilTheFindingIsDelivered() {
        // The heartbeat asks on every due tick, including the ones that
        // end in no wakeup at all. A probe spent on a report nobody got
        // is a probe gone.
        ThinkProcessDocument parked = childProcess("ask-worker", ThinkProcessStatus.IDLE);
        parked.setEngineParamOverrides(new LinkedHashMap<>(Map.of(
                de.mhus.vance.brain.trillian.tools.TrillianAskTool.PARAM_ASK_BLOCKER, "state")));
        ThinkProcessDocument stuck = childProcess("looper", ThinkProcessStatus.BLOCKED);
        stuck.setEngineParamOverrides(new LinkedHashMap<>(Map.of(
                TrillianNatureAdam.PARAM_BLOCKED_SEEN,
                TrillianNatureAdam.MAX_BLOCKED_RESUMES - 1)));
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(parked, stuck));

        assertThat(adam().selfCheckFindings(loopProcess())).hasSize(2);

        verify(thinkProcessService, never())
                .setEngineParamOverride(any(), any(), any());
        verify(thinkProcessService, never()).closeProcess(any(), any());
    }

    @Test
    void anOpenBreakerStaysShut_whileTheCooldownRuns() {
        ThinkProcessDocument parked = childProcess("ask-worker", ThinkProcessStatus.IDLE);
        parked.setEngineParamOverrides(new LinkedHashMap<>(Map.of(
                de.mhus.vance.brain.trillian.tools.TrillianAskTool.PARAM_ASK_BLOCKER, "state",
                TrillianNatureAdam.PARAM_ASK_PROBES, TrillianNatureAdam.MAX_ASK_PROBES,
                TrillianNatureAdam.PARAM_ASK_OPENED_AT, java.time.Instant.now().toEpochMilli())));
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(parked));

        assertThat(adam().selfCheckFindings(loopProcess()).get(0).detail())
                .doesNotContain("re-check");
    }

    @Test
    void aWorkerBlockedTooOften_isStopped() {
        // The episode has to end. Before this the finding said "do not
        // resume" and nobody closed it, so it was reported again every
        // round for good.
        ThinkProcessDocument stuck = childProcess("looper", ThinkProcessStatus.BLOCKED);
        stuck.setEngineParamOverrides(new LinkedHashMap<>(Map.of(
                TrillianNatureAdam.PARAM_BLOCKED_SEEN,
                TrillianNatureAdam.MAX_BLOCKED_RESUMES - 1)));
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(stuck));
        when(thinkProcessService.findById("child-looper"))
                .thenReturn(java.util.Optional.of(stuck));

        TrillianNatureAdam adam = adam();
        java.util.List<SelfCheckFinding> findings = adam.selfCheckFindings(loopProcess());
        adam.selfCheckDelivered(loopProcess(), findings);

        verify(thinkProcessService).closeProcess(
                "child-looper", de.mhus.vance.api.thinkprocess.CloseReason.STOPPED);
        assertThat(findings.get(0).detail()).contains("was stopped");
    }

    @Test
    void aRunningWorker_isNotWorthWakingFor() {
        // It will report by itself; waking to look at it is noise.
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(
                        childProcess("busy", ThinkProcessStatus.RUNNING)));

        assertThat(adam().selfCheckFindings(loopProcess())).isEmpty();
    }

    @Test
    void aRunningWorkerGoneQuiet_isWorthWakingFor() {
        // Still RUNNING, but nothing for an hour — either it is deep in
        // something or it died without saying so, and only a look tells
        // which.
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(
                        childProcess("quiet", ThinkProcessStatus.RUNNING, 90)));

        java.util.List<SelfCheckFinding> findings = adam().selfCheckFindings(loopProcess());

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind()).isEqualTo(SelfCheckFinding.Kind.WORKER_SILENT);
    }

    @Test
    void aBlockedWorker_isReportedWithAResumeDecision() {
        when(thinkProcessService.findByParentProcessId("loop-1"))
                .thenReturn(java.util.List.of(
                        childProcess("stuck", ThinkProcessStatus.BLOCKED)));

        SelfCheckFinding finding = adam().selfCheckFindings(loopProcess()).get(0);

        assertThat(finding.kind()).isEqualTo(SelfCheckFinding.Kind.WORKER_BLOCKED);
        assertThat(finding.detail()).contains("process_steer").contains("transcript");
    }

    @Test
    void natureVoid_neverWakesItself() {
        // The baseline is reactive by definition — no findings, so the
        // heartbeat drops the wakeup without spending a turn.
        assertThat(new TrillianNatureVoid(thinkProcessService).selfCheckFindings(loopProcess()))
                .isEmpty();
    }

    @Test
    void adamInheritsTheAttributeRendering() {
        // Rendering comes from TrillianNatureBase, not from Nature void: it
        // is shared mechanics, and adam must not pick up whatever
        // generation zero does with it later.
        ThinkProcessDocument worker = worker(ACCOUNT);
        worker.getEngineParams().put("attributes", Map.of("tone", "sachlich"));

        assertThat(adam().userPromptAddendum(worker)).contains("tone").contains("sachlich");
    }

    @Test
    void theRegistryAcceptsAdam() {
        // The id travels into _trillian-adam-XXXX and three recipe names,
        // so it has to survive the boot-time validation.
        TrillianNatureRegistry registry = new TrillianNatureRegistry(
                java.util.List.of(new TrillianNatureVoid(thinkProcessService), adam()));

        assertThat(registry.resolve(TrillianNatureAdam.ID).id()).isEqualTo("adam");
        assertThat(registry.getDefault().id()).isEqualTo(TrillianNatureVoid.ID);
    }

    private TrillianNatureAdam adam() {
        return new TrillianNatureAdam(
                thinkProcessService, attributeStore, journalStore, lightLlm, characterCatalog);
    }

    private void givenReflexion(boolean keep, String entry) {
        when(lightLlm.callForJson(any())).thenReturn(Map.of("keep", keep, "entry", entry));
    }

    private static ThinkProcessDocument loopProcess() {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("loop-1");
        p.setTenantId(TENANT);
        p.setProjectId(PROJECT);
        p.setEngineParams(new LinkedHashMap<>());
        return p;
    }

    private static ThinkProcessDocument childProcess(
            String name, ThinkProcessStatus status) {
        return childProcess(name, status, /*minutesAgo*/ 2);
    }

    private static ThinkProcessDocument childProcess(
            String name, ThinkProcessStatus status, int minutesAgo) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("child-" + name);
        p.setName(name);
        p.setStatus(status);
        p.setUpdatedAt(java.time.Instant.now().minusSeconds(minutesAgo * 60L));
        return p;
    }

    private static ThinkProcessDocument worker(String account) {
        ThinkProcessDocument p = new ThinkProcessDocument();
        p.setId("worker-proc");
        p.setTenantId(TENANT);
        p.setProjectId(PROJECT);
        Map<String, Object> params = new LinkedHashMap<>();
        if (account != null) {
            params.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, account);
        }
        p.setEngineParams(params);
        return p;
    }
}
