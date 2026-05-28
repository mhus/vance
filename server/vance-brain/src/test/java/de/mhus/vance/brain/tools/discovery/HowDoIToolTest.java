package de.mhus.vance.brain.tools.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.discovery.DiscoveryResult;
import de.mhus.vance.brain.discovery.DiscoveryService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HowDoIToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "proj-1", "sess-1", "proc-1", "user-1");

    private DiscoveryService discoveryService;
    private HowDoITool tool;

    @BeforeEach
    void setUp() {
        discoveryService = mock(DiscoveryService.class);
        tool = new HowDoITool(discoveryService);
    }

    @Test
    void tool_metadata_is_consistent() {
        assertThat(tool.name()).isEqualTo("how_do_i");
        assertThat(tool.description()).contains("discovery");
        assertThat(tool.primary()).isTrue();
        assertThat(tool.labels()).contains("read-only");

        Map<String, Object> schema = tool.paramsSchema();
        assertThat(schema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("intent");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).contains("intent");
    }

    @Test
    void invoke_relays_intent_and_scope_to_DiscoveryService() {
        when(discoveryService.discover(any(), any(), any(), any())).thenReturn(
                DiscoveryResult.builder()
                        .intent("show pic")
                        .alternatives(List.of())
                        .hint("none")
                        .build());

        Map<String, Object> result = tool.invoke(Map.of("intent", "show pic"), CTX);

        verify(discoveryService).discover(
                eq("show pic"), eq("acme"), eq("proj-1"), eq("proc-1"));
        assertThat(result).containsEntry("intent", "show pic");
        assertThat(result).containsEntry("hint", "none");
        assertThat(result.get("alternatives")).isEqualTo(List.of());
        assertThat(result).containsEntry("loaded", null);
    }

    @Test
    void invoke_serialises_loaded_match_with_inlined_content() {
        // DiscoveryService server-side-loads the body for a confident
        // manual pick — the tool just passes it through.
        DiscoveryResult.Match match = DiscoveryResult.Match.builder()
                .type("manual")
                .name("embed-images")
                .source("engine")
                .summary("How to embed images.")
                .content("# Embedding — Images\n\nFull body inline.")
                .build();
        when(discoveryService.discover(any(), any(), any(), any())).thenReturn(
                DiscoveryResult.builder()
                        .intent("show pic")
                        .loaded(match)
                        .alternatives(List.of())
                        .build());

        Map<String, Object> result = tool.invoke(Map.of("intent", "show pic"), CTX);

        @SuppressWarnings("unchecked")
        Map<String, Object> loaded = (Map<String, Object>) result.get("loaded");
        assertThat(loaded).containsEntry("name", "embed-images");
        assertThat(loaded).containsEntry("source", "engine");
        assertThat(loaded).containsEntry("summary", "How to embed images.");
        assertThat(loaded).containsEntry("content", "# Embedding — Images\n\nFull body inline.");
    }

    @Test
    void invoke_serialises_alternatives_without_content() {
        DiscoveryResult.Match alt = DiscoveryResult.Match.builder()
                .type("manual")
                .name("embed-overview")
                .source("engine")
                .summary("Routing index")
                .score(0.7)
                .build();
        when(discoveryService.discover(any(), any(), any(), any())).thenReturn(
                DiscoveryResult.builder()
                        .intent("hmm")
                        .alternatives(List.of(alt))
                        .build());

        Map<String, Object> result = tool.invoke(Map.of("intent", "hmm"), CTX);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> alternatives =
                (List<Map<String, Object>>) result.get("alternatives");
        assertThat(alternatives).hasSize(1);
        Map<String, Object> first = alternatives.get(0);
        assertThat(first).containsEntry("name", "embed-overview");
        assertThat(first).containsEntry("summary", "Routing index");
        assertThat(first).containsEntry("score", 0.7);
        assertThat(first).doesNotContainKey("content");
    }

    @Test
    void invoke_rejects_missing_tenant() {
        ToolInvocationContext noTenant = new ToolInvocationContext(
                "", null, null, null, null);
        assertThatThrownBy(() -> tool.invoke(Map.of("intent", "x"), noTenant))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("tenant");
        verify(discoveryService, never()).discover(any(), any(), any(), any());
    }

    @Test
    void invoke_rejects_blank_intent() {
        assertThatThrownBy(() -> tool.invoke(Map.of("intent", "   "), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("intent");
        verify(discoveryService, never()).discover(any(), any(), any(), any());
    }

    @Test
    void invoke_rejects_missing_intent() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("intent");
    }

    @Test
    void invoke_rejects_overlong_intent() {
        String huge = "x".repeat(600);
        assertThatThrownBy(() -> tool.invoke(Map.of("intent", huge), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("500");
    }

    @Test
    void invoke_wraps_LightLlmException_as_ToolException() {
        when(discoveryService.discover(any(), any(), any(), any()))
                .thenThrow(new LightLlmException("recipe not found: how-do-i"));

        assertThatThrownBy(() -> tool.invoke(Map.of("intent", "x"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("how_do_i failed");
    }
}
