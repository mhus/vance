package de.mhus.vance.brain.trillian.nature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.trillian.TrillianHeadService;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The multi-headed side of the axis: what a concrete Nature inherits by
 * extending {@link TrillianNatureMultiHead} — the side itself, and the
 * head-aware prompt addendum dispatch that keeps
 * {@code controlPromptAddendum} from having to know whether it is talking
 * to the human's control session or to a conversation head.
 */
class TrillianNatureMultiHeadTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);

    @Test
    void colin_isMultiHeaded() {
        TrillianNatureColin colin = new TrillianNatureColin(thinkProcessService);

        assertThat(colin.multiHead()).isTrue();
        assertThat(colin.headRecipe()).isEqualTo("trillian-head-colin");
        assertThat(colin.callName(Map.of())).isEqualTo("Colin");
    }

    @Test
    void void_staysSingleHeaded() {
        // The axis is a hierarchy position, not a mood: adding a multi-headed
        // Nature must not move the single-headed ones.
        TrillianNatureVoid voidNature = new TrillianNatureVoid(thinkProcessService);

        assertThat(voidNature.multiHead()).isFalse();
    }

    @Test
    void aHeadProcess_getsTheHeadAddendumNextToThePersona() {
        // A head follows the peer link to the loop like any control process —
        // the persona is shared, that is the point — and the Nature's head
        // addendum lands next to it.
        Map<String, Object> loopParams = new LinkedHashMap<>();
        loopParams.put("attributes", Map.of("persona", "cheerful and literal"));
        ThinkProcessDocument loop = new ThinkProcessDocument();
        loop.setId("loop-1");
        loop.setEngineParams(loopParams);
        when(thinkProcessService.findById(any())).thenReturn(Optional.of(loop));

        TrillianNatureColin colin = new TrillianNatureColin(thinkProcessService) {
            @Override
            public String headPromptAddendum(ThinkProcessDocument head) {
                return "## In a conversation\nAlways answer in the thread.";
            }
        };

        String addendum = colin.controlPromptAddendum(headProcess());

        assertThat(addendum).contains("persona").contains("In a conversation").contains("Always answer in the thread.");
    }

    @Test
    void theHumansControlSession_getsThePlainPersona_noHeadDoctrine() {
        Map<String, Object> loopParams = new LinkedHashMap<>();
        loopParams.put("attributes", Map.of("persona", "cheerful and literal"));
        ThinkProcessDocument loop = new ThinkProcessDocument();
        loop.setId("loop-1");
        loop.setEngineParams(loopParams);
        when(thinkProcessService.findById(any())).thenReturn(Optional.of(loop));

        TrillianNatureColin colin = new TrillianNatureColin(thinkProcessService);

        Map<String, Object> controlParams = new LinkedHashMap<>();
        controlParams.put(TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID, "loop-1");
        ThinkProcessDocument control = new ThinkProcessDocument();
        control.setEngineParams(controlParams);

        String addendum = colin.controlPromptAddendum(control);

        assertThat(addendum).contains("persona").doesNotContain("thread");
    }

    private static ThinkProcessDocument headProcess() {
        ThinkProcessDocument head = new ThinkProcessDocument();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(TrillianHeadService.PARAM_HEAD_THREAD, "thread-1");
        params.put(TrillianSessionBootstrapper.PARAM_PEER_PROCESS_ID, "loop-1");
        head.setEngineParams(params);
        return head;
    }
}
