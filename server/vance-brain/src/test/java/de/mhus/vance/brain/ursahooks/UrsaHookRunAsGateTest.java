package de.mhus.vance.brain.ursahooks;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.api.ursahooks.UrsaHookSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Impersonation gate for hooks (code-review / permission-system §4.3a):
 * an explicit {@code runAs:} is only honored when the hook's source
 * document is privileged; otherwise the hook runs under its creator's
 * identity. A non-privileged hook must never escalate to a foreign user.
 */
class UrsaHookRunAsGateTest {

    @Test
    void nonPrivileged_hook_ignores_foreign_runAs_falls_back_to_creator() {
        UrsaHookDef def = def(/*privileged*/ false, /*runAs*/ "victim", /*creator*/ "author");

        assertThat(UrsaHookDispatcher.effectiveRunAs(def)).isEqualTo("author");
    }

    @Test
    void privileged_hook_honors_foreign_runAs() {
        UrsaHookDef def = def(/*privileged*/ true, /*runAs*/ "victim", /*creator*/ "author");

        assertThat(UrsaHookDispatcher.effectiveRunAs(def)).isEqualTo("victim");
    }

    @Test
    void no_runAs_uses_creator_regardless_of_privilege() {
        assertThat(UrsaHookDispatcher.effectiveRunAs(def(false, null, "author")))
                .isEqualTo("author");
        assertThat(UrsaHookDispatcher.effectiveRunAs(def(true, null, "author")))
                .isEqualTo("author");
    }

    private static UrsaHookDef def(boolean privileged, String runAs, String creator) {
        TriggerAction action = TriggerAction.Recipe.of(
                "notify", /*initialMessage*/ null, /*params*/ null, runAs);
        return new UrsaHookDef(
                "h", UrsaHookEventName.PROCESS_COMPLETED, UrsaHookSource.PROJECT, true,
                /*description*/ null, Duration.ofSeconds(5), /*tags*/ null,
                /*yamlBody*/ "recipe: notify\n", creator, privileged, action);
    }
}
