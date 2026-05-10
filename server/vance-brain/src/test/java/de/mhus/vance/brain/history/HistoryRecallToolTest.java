package de.mhus.vance.brain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link HistoryRecallTool}: parameter validation, hard cap on the
 * number of recalled turns, and that the service call carries the
 * tenant + process scope from the invocation context.
 */
class HistoryRecallToolTest {

    private final ChatMessageService service = mock(ChatMessageService.class);
    private final HistoryRecallTool tool = new HistoryRecallTool(service);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "tenant-1", "proj", "sess", "process-abc", "user");

    @Test
    void invoke_withoutProcessScope_throws() {
        ToolInvocationContext noProcess =
                new ToolInvocationContext("t", "p", "s", null, "u");

        assertThatThrownBy(() -> tool.invoke(Map.of("turnIds", List.of("m-1")), noProcess))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("process scope");
    }

    @Test
    void invoke_missingTurnIds_rejected() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("turnIds");
    }

    @Test
    void invoke_emptyTurnIds_rejected() {
        assertThatThrownBy(() -> tool.invoke(Map.of("turnIds", List.of()), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("turnIds");
    }

    @Test
    void invoke_aboveCap_rejected() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < HistoryRecallTool.MAX_RECALL + 1; i++) tooMany.add("m-" + i);

        assertThatThrownBy(() -> tool.invoke(Map.of("turnIds", tooMany), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining(String.valueOf(HistoryRecallTool.MAX_RECALL));
    }

    @Test
    void invoke_passesTenantAndProcessToService() {
        when(service.findByIds(any(), any(), any())).thenReturn(List.of());

        tool.invoke(Map.of("turnIds", List.of("m-1", "m-2")), ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> idsCap =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(service).findByIds(eq("tenant-1"), eq("process-abc"), idsCap.capture());
        assertThat(idsCap.getValue()).containsExactlyInAnyOrder("m-1", "m-2");
    }

    @Test
    void invoke_returnsFullContent_inServiceOrder() {
        ChatMessageDocument m1 = ChatMessageDocument.builder()
                .id("m-1").role(ChatRole.USER).content("first")
                .tenantId("tenant-1").thinkProcessId("process-abc").build();
        ChatMessageDocument m2 = ChatMessageDocument.builder()
                .id("m-2").role(ChatRole.ASSISTANT).content("second")
                .tenantId("tenant-1").thinkProcessId("process-abc").build();
        when(service.findByIds(any(), any(), any())).thenReturn(List.of(m1, m2));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = tool.invoke(Map.of(
                "turnIds", List.of("m-1", "m-2")), ctx);

        assertThat(result.get("count")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> turns = (List<Map<String, Object>>) result.get("turns");
        assertThat(turns).hasSize(2);
        assertThat(turns.get(0).get("turnId")).isEqualTo("m-1");
        assertThat(turns.get(0).get("content")).isEqualTo("first");
        assertThat(turns.get(1).get("content")).isEqualTo("second");
    }

    @Test
    void deferred_andHasSearchHint() {
        assertThat(tool.deferred()).isTrue();
        assertThat(tool.primary()).isFalse();
        assertThat(tool.searchHint()).isNotBlank();
        assertThat(tool.labels()).containsExactly("read-only");
    }
}
