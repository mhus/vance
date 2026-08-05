package de.mhus.vance.brain.thinkengine.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.skill.ActiveSkillRefEmbedded;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the one-shot drain hoisted into {@link StructuredActionEngine}
 * — the piece Arthur and Eddie were missing (only Ford/Frankie carried
 * their own copy), so a {@code /skill <name> --once} on the default
 * session chat used to stick forever. Same-package so the protected
 * {@code dropOneShotSkills} is reachable; the seven unused constructor
 * deps are null because the method only touches {@link ThinkProcessService}
 * and the process doc.
 */
@ExtendWith(MockitoExtension.class)
class StructuredActionEngineOneShotTest {

    @Mock private ThinkProcessService thinkProcessService;

    private StructuredActionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new TestEngine(thinkProcessService);
    }

    private ActiveSkillRefEmbedded skill(String name, boolean oneShot) {
        return ActiveSkillRefEmbedded.builder().name(name).oneShot(oneShot).build();
    }

    @Test
    void dropOneShotSkills_removesOneShotButKeepsSticky() {
        ThinkProcessDocument process = ThinkProcessDocument.builder()
                .id("p1")
                .activeSkills(new ArrayList<>(List.of(
                        skill("sticky", false), skill("code-review", true))))
                .build();

        engine.dropOneShotSkills(process);

        assertThat(process.getActiveSkills())
                .extracting(ActiveSkillRefEmbedded::getName)
                .containsExactly("sticky");
        verify(thinkProcessService).replaceActiveSkills(eq("p1"), any());
    }

    @Test
    void dropOneShotSkills_noOneShotActive_doesNotPersist() {
        ThinkProcessDocument process = ThinkProcessDocument.builder()
                .id("p1")
                .activeSkills(new ArrayList<>(List.of(skill("sticky", false))))
                .build();

        engine.dropOneShotSkills(process);

        assertThat(process.getActiveSkills())
                .extracting(ActiveSkillRefEmbedded::getName)
                .containsExactly("sticky");
        verify(thinkProcessService, never()).replaceActiveSkills(anyString(), any());
    }

    @Test
    void dropOneShotSkills_emptyList_isNoOp() {
        ThinkProcessDocument empty = ThinkProcessDocument.builder()
                .id("p1").activeSkills(new ArrayList<>()).build();

        engine.dropOneShotSkills(empty);

        verify(thinkProcessService, never()).replaceActiveSkills(anyString(), any());
    }

    /**
     * Minimal concrete {@link StructuredActionEngine} — every {@code ThinkEngine}
     * / action-contract method stubbed since the test only exercises the
     * base {@code dropOneShotSkills} helper.
     */
    private static final class TestEngine extends StructuredActionEngine {
        TestEngine(ThinkProcessService tps) {
            super(null, null, null, null, null, null, tps, null);
        }

        @Override public String name() { return "test-engine"; }
        @Override public String title() { return "Test Engine"; }
        @Override public String description() { return "test"; }
        @Override public String version() { return "1.0.0"; }
        @Override public void start(ThinkProcessDocument p, ThinkEngineContext c) { }
        @Override public void resume(ThinkProcessDocument p, ThinkEngineContext c) { }
        @Override public void suspend(ThinkProcessDocument p, ThinkEngineContext c) { }
        @Override public void steer(ThinkProcessDocument p, ThinkEngineContext c, SteerMessage m) { }
        @Override public void stop(ThinkProcessDocument p, ThinkEngineContext c) { }

        @Override protected String actionToolName() { return "test_action"; }
        @Override protected String actionToolDescription() { return "test"; }
        @Override protected Map<String, Object> actionToolSchema() { return Map.of(); }
        @Override protected Set<String> supportedActionTypes() { return Set.of(); }
        @Override protected ActionTurnOutcome handleAction(
                EngineAction action, ThinkProcessDocument process, ThinkEngineContext ctx) {
            return null;
        }
    }
}
