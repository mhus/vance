package de.mhus.vance.brain.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.api.hooks.HookSource;
import de.mhus.vance.api.hooks.HookType;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HookRegistryTest {

    @Test
    void replace_putsHooksForEvent() {
        HookRegistry reg = new HookRegistry();
        Map<HookEventName, List<HookDef>> snap = new EnumMap<>(HookEventName.class);
        snap.put(HookEventName.PROCESS_COMPLETED, List.of(def("notify", HookEventName.PROCESS_COMPLETED)));
        reg.replace("acme", "p1", snap);

        assertThat(reg.hooksFor("acme", "p1", HookEventName.PROCESS_COMPLETED))
                .extracting(HookDef::name)
                .containsExactly("notify");
    }

    @Test
    void replaceEvent_addsAndRemoves() {
        HookRegistry reg = new HookRegistry();
        reg.replaceEvent("acme", "p1", HookEventName.PROCESS_FAILED,
                List.of(def("a", HookEventName.PROCESS_FAILED)));
        assertThat(reg.hooksFor("acme", "p1", HookEventName.PROCESS_FAILED)).hasSize(1);
        reg.replaceEvent("acme", "p1", HookEventName.PROCESS_FAILED, List.of());
        assertThat(reg.hooksFor("acme", "p1", HookEventName.PROCESS_FAILED)).isEmpty();
    }

    @Test
    void clear_dropsEverythingForProject() {
        HookRegistry reg = new HookRegistry();
        Map<HookEventName, List<HookDef>> snap = new EnumMap<>(HookEventName.class);
        snap.put(HookEventName.PROCESS_COMPLETED, List.of(def("x", HookEventName.PROCESS_COMPLETED)));
        snap.put(HookEventName.PROCESS_FAILED, List.of(def("y", HookEventName.PROCESS_FAILED)));
        reg.replace("acme", "p1", snap);
        assertThat(reg.allFor("acme", "p1")).hasSize(2);
        reg.clear("acme", "p1");
        assertThat(reg.allFor("acme", "p1")).isEmpty();
    }

    @Test
    void hooksFor_returnsEmptyListForUnknownProject() {
        HookRegistry reg = new HookRegistry();
        assertThat(reg.hooksFor("acme", "nope", HookEventName.PROCESS_COMPLETED)).isEmpty();
    }

    private static HookDef def(String name, HookEventName event) {
        return new HookDef(
                name, event, HookSource.PROJECT, HookType.JS, true,
                /*description*/ null, Duration.ofSeconds(5), null,
                /*yamlBody*/ "type: js\nscript: noop;\n",
                /*createdByUserId*/ null,
                /*script*/ "log.info('hi');",
                /*model*/ null, /*maxTokens*/ null, /*prompt*/ null);
    }
}
