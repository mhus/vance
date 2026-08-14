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
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * What separates adam from Nature-0 is durability, so that is what these
 * check: the map goes to the store, comes back from it, and Nature-0
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

    @Test
    void aChangedMap_isMirroredToTheStore() {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("persona", "witziger Schwabe");

        adam().attributesChanged(worker(ACCOUNT), attrs);

        verify(attributeStore).save(TENANT, PROJECT, ACCOUNT, attrs);
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
    void natureZero_persistsNothing() {
        TrillianNature0 zero = new TrillianNature0(thinkProcessService);

        zero.attributesChanged(worker(ACCOUNT), Map.of("a", "b"));

        // Nature-0 is ephemeral by definition; if this ever started
        // writing, the two generations would stop differing.
        assertThat(zero.initialAttributes(TENANT, PROJECT, ACCOUNT)).isEmpty();
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
    void natureZero_doesNotReflect() {
        TrillianNature0 zero = new TrillianNature0(thinkProcessService);

        zero.taskConcluded(worker(ACCOUNT), "task-1",
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
    void adamInheritsTheAttributeRendering() {
        // Rendering comes from TrillianNatureBase, not from Nature-0: it
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
                java.util.List.of(new TrillianNature0(thinkProcessService), adam()));

        assertThat(registry.resolve(TrillianNatureAdam.ID).id()).isEqualTo("adam");
        assertThat(registry.getDefault().id()).isEqualTo(TrillianNature0.ID);
    }

    private TrillianNatureAdam adam() {
        return new TrillianNatureAdam(
                thinkProcessService, attributeStore, journalStore, lightLlm);
    }

    private void givenReflexion(boolean keep, String entry) {
        when(lightLlm.callForJson(any())).thenReturn(Map.of("keep", keep, "entry", entry));
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
