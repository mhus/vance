package de.mhus.vance.brain.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.SpawnTool;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolBus;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpawnToolRegistryTest {

    @Test
    void registry_collects_only_annotated_tools_after_rescan() {
        SpawnToolRegistry reg = new SpawnToolRegistry(providerOf(List.of(
                stubTool("plain", FakePlainTool.class),
                stubTool("spawn_a", FakeSpawnToolA.class),
                stubTool("spawn_b", FakeSpawnToolB.class))));
        reg.rescan();

        assertThat(reg.spawnToolNames()).containsExactlyInAnyOrder("spawn_a", "spawn_b");
        assertThat(reg.isSpawnTool("plain")).isFalse();
        assertThat(reg.isSpawnTool("spawn_a")).isTrue();
        assertThat(reg.isSpawnTool("unknown")).isFalse();
    }

    @Test
    void registry_is_empty_until_rescan() {
        SpawnToolRegistry reg = new SpawnToolRegistry(providerOf(List.of(
                stubTool("spawn_a", FakeSpawnToolA.class))));

        assertThat(reg.spawnToolNames()).isEmpty();
        reg.rescan();
        assertThat(reg.spawnToolNames()).containsExactly("spawn_a");
    }

    @Test
    void empty_tool_list_yields_empty_registry() {
        SpawnToolRegistry reg = new SpawnToolRegistry(providerOf(List.of()));
        reg.rescan();
        assertThat(reg.spawnToolNames()).isEmpty();
    }

    @Test
    void spawnToolNames_returned_set_is_immutable() {
        SpawnToolRegistry reg = new SpawnToolRegistry(providerOf(List.of(
                stubTool("a", FakeSpawnToolA.class))));
        reg.rescan();
        Set<String> names = reg.spawnToolNames();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> names.add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<List<Tool>> providerOf(List<Tool> tools) {
        org.springframework.beans.factory.ObjectProvider<List<Tool>> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(tools);
        return p;
    }

    // ──────────────────── Helpers ────────────────────

    private static Tool stubTool(String name, Class<? extends Tool> backingClass) {
        Tool t = mock(backingClass);
        when(t.name()).thenReturn(name);
        return t;
    }

    public static class FakePlainTool implements Tool {
        @Override public String name() { return "plain"; }
        @Override public String description() { return ""; }
        @Override public boolean primary() { return true; }
        @Override public Map<String, Object> paramsSchema() { return Map.of(); }
        @Override public Map<String, Object> invoke(
                Map<String, Object> p, ToolInvocationContext ctx) { return Map.of(); }
        @Override public Map<String, Object> invoke(
                Map<String, Object> p, ToolInvocationContext ctx, ToolBus bus) { return Map.of(); }
    }

    @SpawnTool
    public static class FakeSpawnToolA implements Tool {
        @Override public String name() { return "spawn_a"; }
        @Override public String description() { return ""; }
        @Override public boolean primary() { return true; }
        @Override public Map<String, Object> paramsSchema() { return Map.of(); }
        @Override public Map<String, Object> invoke(
                Map<String, Object> p, ToolInvocationContext ctx) { return Map.of(); }
        @Override public Map<String, Object> invoke(
                Map<String, Object> p, ToolInvocationContext ctx, ToolBus bus) { return Map.of(); }
    }

    @SpawnTool
    public static class FakeSpawnToolB implements Tool {
        @Override public String name() { return "spawn_b"; }
        @Override public String description() { return ""; }
        @Override public boolean primary() { return true; }
        @Override public Map<String, Object> paramsSchema() { return Map.of(); }
        @Override public Map<String, Object> invoke(
                Map<String, Object> p, ToolInvocationContext ctx) { return Map.of(); }
        @Override public Map<String, Object> invoke(
                Map<String, Object> p, ToolInvocationContext ctx, ToolBus bus) { return Map.of(); }
    }
}
