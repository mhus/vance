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
    void adamInheritsTheAttributeRendering() {
        // The prompt side is not what a generation changes — inheriting
        // it is the point, and a silent regression here would make adam
        // look like it lost its attributes.
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
        return new TrillianNatureAdam(thinkProcessService, attributeStore);
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
