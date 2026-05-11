package de.mhus.vance.brain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link ListEditedResourcesTool}: the specialised one-call surface for
 * "what have I touched". Validates the param parsing, the
 * since/sinceTag resolution priority, scope plumbing, and the response
 * shape (type/key split, scope echo, resolvedFrom hint).
 */
class ListEditedResourcesToolTest {

    private final ChatMessageService service = mock(ChatMessageService.class);
    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ListEditedResourcesTool tool = new ListEditedResourcesTool(
            service, thinkProcessService);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "tenant-1", "proj", "sess", "process-abc", "user");

    @Test
    void deferred_andHasSearchHint() {
        assertThat(tool.deferred()).isTrue();
        assertThat(tool.primary()).isFalse();
        assertThat(tool.searchHint()).isNotBlank();
        assertThat(tool.labels()).containsExactly("read-only");
    }

    @Test
    void invoke_requiresProcessScope() {
        ToolInvocationContext noProcess =
                new ToolInvocationContext("t", "p", "s", null, "u");

        assertThatThrownBy(() -> tool.invoke(Map.of(), noProcess))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("process scope");
    }

    @Test
    void invoke_defaultScopeAndNoFloor_yieldsAllResources() {
        when(service.distinctResourceKeys(eq("tenant-1"), any(), eq(null)))
                .thenReturn(List.of(
                        "CLIENT_FILE:/abs/Foo.java",
                        "DOCUMENT:65f-doc"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = tool.invoke(Map.of(), ctx);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resources =
                (List<Map<String, Object>>) result.get("resources");
        assertThat(resources).hasSize(2);
        assertThat(resources.get(0))
                .containsEntry("type", "CLIENT_FILE")
                .containsEntry("key", "/abs/Foo.java");
        assertThat(resources.get(1))
                .containsEntry("type", "DOCUMENT")
                .containsEntry("key", "65f-doc");
        assertThat(result.get("scope")).isEqualTo("process");
        assertThat(result).doesNotContainKey("since");
        assertThat(result).doesNotContainKey("resolvedFrom");
    }

    @Test
    void invoke_withSince_passesTimestampToService() {
        Instant since = Instant.parse("2026-05-11T14:00:00Z");
        when(service.distinctResourceKeys(eq("tenant-1"), any(), eq(since)))
                .thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = tool.invoke(Map.of("since", since.toString()), ctx);

        assertThat(result.get("since")).isEqualTo(since.toString());
        assertThat(result.get("resolvedFrom")).isEqualTo("since");

        ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
        verify(service).distinctResourceKeys(eq("tenant-1"), any(), sinceCap.capture());
        assertThat(sinceCap.getValue()).isEqualTo(since);
    }

    @Test
    void invoke_withSinceTag_resolvesToMarkerTimestamp_andEchoesResolvedFrom() {
        Instant markerTime = Instant.parse("2026-05-11T15:30:00Z");
        when(service.findLatestCreatedAtForTag(
                eq("tenant-1"), any(), eq("PLAN_STEP_STARTED:cleanup")))
                .thenReturn(Optional.of(markerTime));
        when(service.distinctResourceKeys(eq("tenant-1"), any(), eq(markerTime)))
                .thenReturn(List.of("CLIENT_FILE:/abs/Bar.java"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = tool.invoke(Map.of(
                "sinceTag", "PLAN_STEP_STARTED:cleanup"), ctx);

        assertThat(result.get("since")).isEqualTo(markerTime.toString());
        assertThat(result.get("resolvedFrom"))
                .isEqualTo("sinceTag:PLAN_STEP_STARTED:cleanup");
    }

    @Test
    void invoke_sinceTagBeatsSince_whenBothPresent() {
        Instant rawSince = Instant.parse("2026-05-11T10:00:00Z");
        Instant markerTime = Instant.parse("2026-05-11T15:30:00Z");
        when(service.findLatestCreatedAtForTag(
                eq("tenant-1"), any(), eq("MODE:execute")))
                .thenReturn(Optional.of(markerTime));
        when(service.distinctResourceKeys(eq("tenant-1"), any(), eq(markerTime)))
                .thenReturn(List.of());

        tool.invoke(Map.of(
                "since", rawSince.toString(),
                "sinceTag", "MODE:execute"), ctx);

        // The marker time, not the raw `since`, must be the floor —
        // sinceTag wins to keep semantic anchoring stable.
        ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
        verify(service).distinctResourceKeys(eq("tenant-1"), any(), sinceCap.capture());
        assertThat(sinceCap.getValue()).isEqualTo(markerTime);
    }

    @Test
    void invoke_sinceTagNotFound_fallsBackToSince() {
        Instant rawSince = Instant.parse("2026-05-11T10:00:00Z");
        when(service.findLatestCreatedAtForTag(
                eq("tenant-1"), any(), eq("PLAN_STEP_STARTED:never")))
                .thenReturn(Optional.empty());
        when(service.distinctResourceKeys(eq("tenant-1"), any(), eq(rawSince)))
                .thenReturn(List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = tool.invoke(Map.of(
                "since", rawSince.toString(),
                "sinceTag", "PLAN_STEP_STARTED:never"), ctx);

        assertThat(result.get("since")).isEqualTo(rawSince.toString());
        // resolvedFrom reflects the actual resolution (since), not the
        // requested sinceTag — the LLM can see the marker did not match.
        assertThat(result.get("resolvedFrom")).isEqualTo("since");
    }

    @Test
    void invoke_sinceTagAlone_notFound_yieldsNoFloor() {
        when(service.findLatestCreatedAtForTag(
                eq("tenant-1"), any(), eq("FILE_EDIT")))
                .thenReturn(Optional.empty());
        when(service.distinctResourceKeys(eq("tenant-1"), any(), eq(null)))
                .thenReturn(List.of("DOCUMENT:65f"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = tool.invoke(Map.of("sinceTag", "FILE_EDIT"), ctx);

        assertThat(result).doesNotContainKey("since");
        assertThat(result).doesNotContainKey("resolvedFrom");
    }

    @Test
    void invoke_scopeChildren_widensProcessSet() {
        when(thinkProcessService.findDescendantIds("process-abc"))
                .thenReturn(Set.of("process-abc", "child-1", "child-2"));
        when(service.distinctResourceKeys(eq("tenant-1"), any(), eq(null)))
                .thenReturn(List.of());

        tool.invoke(Map.of("scope", "children"), ctx);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> scopeCap = ArgumentCaptor.forClass(Set.class);
        verify(service).distinctResourceKeys(eq("tenant-1"), scopeCap.capture(), any());
        assertThat(scopeCap.getValue())
                .containsExactlyInAnyOrder("process-abc", "child-1", "child-2");
    }

    @Test
    void invoke_invalidScope_rejected() {
        assertThatThrownBy(() ->
                tool.invoke(Map.of("scope", "everything"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void invoke_malformedSince_rejected() {
        assertThatThrownBy(() ->
                tool.invoke(Map.of("since", "yesterday"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void invoke_resourcesWithMultiColonKeys_splitOnFirstColonOnly() {
        // RESOURCE keys like "WORKSPACE:proc-abc/notes.md" have multiple
        // ":" segments — split must use the FIRST colon as separator so
        // the full sub-key is preserved.
        when(service.distinctResourceKeys(eq("tenant-1"), any(), eq(null)))
                .thenReturn(List.of("WORKSPACE:proc-abc/notes.md"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = tool.invoke(Map.of(), ctx);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resources =
                (List<Map<String, Object>>) result.get("resources");
        assertThat(resources.get(0))
                .containsEntry("type", "WORKSPACE")
                .containsEntry("key", "proc-abc/notes.md");
    }
}
