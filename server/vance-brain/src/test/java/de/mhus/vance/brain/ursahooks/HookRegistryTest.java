package de.mhus.vance.brain.ursahooks;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.ursahooks.UrsaHookEventName;
import de.mhus.vance.api.ursahooks.UrsaHookSource;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HookRegistryTest {

    @Test
    void replace_putsHooksForEvent() {
        UrsaHookRegistry reg = new UrsaHookRegistry();
        Map<UrsaHookEventName, List<UrsaHookDef>> snap = new EnumMap<>(UrsaHookEventName.class);
        snap.put(UrsaHookEventName.PROCESS_COMPLETED, List.of(def("notify", UrsaHookEventName.PROCESS_COMPLETED)));
        reg.replace("acme", "p1", snap);

        assertThat(reg.hooksFor("acme", "p1", UrsaHookEventName.PROCESS_COMPLETED))
                .extracting(UrsaHookDef::name)
                .containsExactly("notify");
    }

    @Test
    void replaceEvent_addsAndRemoves() {
        UrsaHookRegistry reg = new UrsaHookRegistry();
        reg.replaceEvent("acme", "p1", UrsaHookEventName.PROCESS_FAILED,
                List.of(def("a", UrsaHookEventName.PROCESS_FAILED)));
        assertThat(reg.hooksFor("acme", "p1", UrsaHookEventName.PROCESS_FAILED)).hasSize(1);
        reg.replaceEvent("acme", "p1", UrsaHookEventName.PROCESS_FAILED, List.of());
        assertThat(reg.hooksFor("acme", "p1", UrsaHookEventName.PROCESS_FAILED)).isEmpty();
    }

    @Test
    void clear_dropsEverythingForProject() {
        UrsaHookRegistry reg = new UrsaHookRegistry();
        Map<UrsaHookEventName, List<UrsaHookDef>> snap = new EnumMap<>(UrsaHookEventName.class);
        snap.put(UrsaHookEventName.PROCESS_COMPLETED, List.of(def("x", UrsaHookEventName.PROCESS_COMPLETED)));
        snap.put(UrsaHookEventName.PROCESS_FAILED, List.of(def("y", UrsaHookEventName.PROCESS_FAILED)));
        reg.replace("acme", "p1", snap);
        assertThat(reg.allFor("acme", "p1")).hasSize(2);
        reg.clear("acme", "p1");
        assertThat(reg.allFor("acme", "p1")).isEmpty();
    }

    @Test
    void hooksFor_returnsEmptyListForUnknownProject() {
        UrsaHookRegistry reg = new UrsaHookRegistry();
        assertThat(reg.hooksFor("acme", "nope", UrsaHookEventName.PROCESS_COMPLETED)).isEmpty();
    }

    private static UrsaHookDef def(String name, UrsaHookEventName event) {
        TriggerAction action = TriggerAction.Recipe.of(
                "notify", /*initialMessage*/ null, /*params*/ null, /*runAs*/ null);
        return new UrsaHookDef(
                name, event, UrsaHookSource.PROJECT, true,
                /*description*/ null, Duration.ofSeconds(5), /*tags*/ null,
                /*yamlBody*/ "recipe: notify\n",
                /*createdByUserId*/ null,
                /*privileged*/ false,
                action);
    }
}
