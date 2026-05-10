package de.mhus.vance.brain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageSearchQuery;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tool-surface contract for {@link HistorySearchTool}: param validation,
 * limit clamping, tenant + process scoping flow into the
 * {@link ChatMessageSearchQuery}, and the response shape (turnIds +
 * snippets, not full content).
 */
class HistorySearchToolTest {

    private final ChatMessageService service = mock(ChatMessageService.class);
    private final HistorySearchTool tool = new HistorySearchTool(service);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "tenant-1", "proj", "sess", "process-abc", "user");

    @Test
    void invoke_withoutProcessScope_throws() {
        ToolInvocationContext noProcess =
                new ToolInvocationContext("t", "p", "s", null, "u");

        assertThatThrownBy(() -> tool.invoke(Map.of(), noProcess))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("process scope");
    }

    @Test
    void invoke_passesTenantAndProcessIntoSearchQuery() {
        when(service.search(any())).thenReturn(List.of());

        tool.invoke(Map.of(), ctx);

        ArgumentCaptor<ChatMessageSearchQuery> cap =
                ArgumentCaptor.forClass(ChatMessageSearchQuery.class);
        verify(service).search(cap.capture());
        ChatMessageSearchQuery q = cap.getValue();
        assertThat(q.tenantId()).isEqualTo("tenant-1");
        assertThat(q.thinkProcessId()).isEqualTo("process-abc");
        assertThat(q.tags()).isEmpty();
        assertThat(q.text()).isNull();
        assertThat(q.since()).isNull();
        assertThat(q.limit()).isEqualTo(ChatMessageSearchQuery.DEFAULT_LIMIT);
    }

    @Test
    void invoke_clampsLimitAboveMax() {
        when(service.search(any())).thenReturn(List.of());

        tool.invoke(Map.of("limit", 9999), ctx);

        ArgumentCaptor<ChatMessageSearchQuery> cap =
                ArgumentCaptor.forClass(ChatMessageSearchQuery.class);
        verify(service).search(cap.capture());
        assertThat(cap.getValue().limit()).isEqualTo(ChatMessageSearchQuery.MAX_LIMIT);
    }

    @Test
    void invoke_passesTagsTextSince() {
        when(service.search(any())).thenReturn(List.of());

        tool.invoke(Map.of(
                "tags", List.of("FILE_EDIT", "ERROR"),
                "query", "provider caching",
                "since", "2026-05-01T00:00:00Z"), ctx);

        ArgumentCaptor<ChatMessageSearchQuery> cap =
                ArgumentCaptor.forClass(ChatMessageSearchQuery.class);
        verify(service).search(cap.capture());
        ChatMessageSearchQuery q = cap.getValue();
        assertThat(q.tags()).containsExactlyInAnyOrder("FILE_EDIT", "ERROR");
        assertThat(q.text()).isEqualTo("provider caching");
        assertThat(q.since()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
    }

    @Test
    void invoke_malformedTags_rejected() {
        assertThatThrownBy(() -> tool.invoke(Map.of("tags", "not-an-array"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("string array");
    }

    @Test
    void invoke_malformedSince_rejected() {
        assertThatThrownBy(() -> tool.invoke(Map.of("since", "yesterday"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void invoke_resultShape_carriesTurnIdAndSnippetButNotFullContent() {
        Set<String> tags = new LinkedHashSet<>();
        tags.add("FILE_EDIT");
        ChatMessageDocument hit = ChatMessageDocument.builder()
                .id("m-1")
                .tenantId("tenant-1")
                .thinkProcessId("process-abc")
                .role(ChatRole.ASSISTANT)
                .content("x".repeat(500))
                .tags(tags)
                .build();
        when(service.search(any())).thenReturn(List.of(hit));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = tool.invoke(Map.of(), ctx);

        assertThat(result.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) result.get("hits");
        assertThat(hits).hasSize(1);
        Map<String, Object> entry = hits.get(0);
        assertThat(entry.get("turnId")).isEqualTo("m-1");
        assertThat(entry.get("role")).isEqualTo("ASSISTANT");
        @SuppressWarnings("unchecked")
        List<String> entryTags = (List<String>) entry.get("tags");
        assertThat(entryTags).contains("FILE_EDIT");
        // Snippet must be truncated; sentinel char follows.
        assertThat(((String) entry.get("snippet"))).hasSize(HistorySearchTool.SNIPPET_CHARS + 1)
                .endsWith("…");
    }

    @Test
    void deferred_andHasSearchHint() {
        assertThat(tool.deferred()).isTrue();
        assertThat(tool.primary()).isFalse();
        assertThat(tool.searchHint()).isNotBlank();
        assertThat(tool.labels()).containsExactly("read-only");
    }
}
