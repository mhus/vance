package de.mhus.vance.brain.tools.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.api.tools.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/**
 * Registry semantics for multi-provider connections: one desktop-app
 * WebView registers its agent tools AND the web UI's state tools over the
 * same WebSocket, so a registration must replace only the {@code source}
 * groups it declares — see {@link ClientToolRegistry#register}.
 */
class ClientToolRegistryTest {

    private final ClientToolRegistry registry = new ClientToolRegistry();

    private static ToolSpec spec(String name, String source) {
        return ToolSpec.builder()
                .name(name)
                .description("test tool " + name)
                .source(source)
                .build();
    }

    @Test
    @DisplayName("First registration stores the tools")
    void firstRegistration() {
        registry.register("s1", "ed1", mock(WebSocketSession.class), List.of(spec("client_file_read", "desktop")));

        assertThat(registry.toolsFor("s1")).extracting(ToolSpec::getName).containsExactly("client_file_read");
    }

    @Test
    @DisplayName("Same editor, other source: both groups survive (desktop app: agent + UI-state tools)")
    void sameEditorOtherSourceMerges() {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register(
                "s1", "ed1", ws, List.of(spec("client_file_read", "desktop"), spec("client_exec_run", "desktop")));
        registry.register("s1", "ed1", ws, List.of(spec("location_get", "chat")));

        assertThat(registry.toolsFor("s1"))
                .extracting(ToolSpec::getName)
                .containsExactlyInAnyOrder("client_file_read", "client_exec_run", "location_get");
        // Routing entry stays on the registering connection.
        assertThat(registry.entry("s1").orElseThrow().editorId()).isEqualTo("ed1");
    }

    @Test
    @DisplayName("Same editor, same source, shorter list: dropped tools disappear, other source survives")
    void sameEditorSameSourceReplaces() {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register(
                "s1", "ed1", ws, List.of(spec("client_file_read", "desktop"), spec("client_file_write", "desktop")));
        registry.register("s1", "ed1", ws, List.of(spec("client_file_read", "desktop")));

        assertThat(registry.toolsFor("s1")).extracting(ToolSpec::getName).containsExactly("client_file_read");

        registry.register("s1", "ed1", ws, List.of(spec("location_get", "chat")));
        registry.register(
                "s1", "ed1", ws, List.of(spec("client_file_read", "desktop"), spec("client_file_write", "desktop")));
        assertThat(registry.toolsFor("s1"))
                .extracting(ToolSpec::getName)
                .containsExactlyInAnyOrder("client_file_read", "client_file_write", "location_get");
    }

    @Test
    @DisplayName("Unsourced flat lists (foot) replace each other wholesale")
    void unsourcedFlatListReplaces() {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register("s1", "ed1", ws, List.of(spec("client_file_read", null), spec("client_exec_run", null)));
        registry.register("s1", "ed1", ws, List.of(spec("client_file_read", null)));

        assertThat(registry.toolsFor("s1")).extracting(ToolSpec::getName).containsExactly("client_file_read");
    }

    @Test
    @DisplayName("Different editor: full replace (single-provider assumption as before)")
    void differentEditorReplaces() {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register("s1", "ed1", ws, List.of(spec("client_file_read", "desktop")));
        registry.register("s1", "ed2", ws, List.of(spec("location_get", "chat")));

        assertThat(registry.toolsFor("s1")).extracting(ToolSpec::getName).containsExactly("location_get");
    }

    @Test
    @DisplayName("find() resolves across the merged groups")
    void findAcrossGroups() {
        WebSocketSession ws = mock(WebSocketSession.class);
        registry.register("s1", "ed1", ws, List.of(spec("client_file_read", "desktop")));
        registry.register("s1", "ed1", ws, List.of(spec("location_get", "chat")));

        assertThat(registry.find("s1", "location_get")).isPresent();
        assertThat(registry.find("s1", "client_file_read")).isPresent();
        assertThat(registry.find("s1", "nope")).isEmpty();
    }
}
