package de.mhus.vance.brain.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.followup.FollowUpSuggestionDto;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.shared.metric.MetricService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Unit tests for {@link FollowUpService}. Mocks the
 * {@link LightLlmService}; tests focus on cursor splitting,
 * count clamping, suggestion parsing, tolerance for the LLM's common
 * drift modes, and the Caffeine-backed result cache.
 */
class FollowUpServiceTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "_tenant";

    private LightLlmService lightLlm;
    private MetricService metrics;
    private FollowUpService service;

    @BeforeEach
    void setUp() {
        lightLlm = mock(LightLlmService.class);
        metrics = new MetricService(new SimpleMeterRegistry());
        service = new FollowUpService(lightLlm, metrics);
    }

    // ── Cursor splitting ───────────────────────────────────────────

    @Test
    void suggest_splits_text_at_cursor_position() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        service.suggest("Hello, world!", 7, 3, null, TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        Map<String, Object> vars = captor.getValue().getPebbleVars();
        assertThat(vars).containsEntry("textBefore", "Hello, ");
        assertThat(vars).containsEntry("textAfter", "world!");
    }

    @Test
    void suggest_handles_cursor_at_start() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        service.suggest("abc", 0, 3, null, TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        Map<String, Object> vars = captor.getValue().getPebbleVars();
        assertThat(vars).containsEntry("textBefore", "");
        assertThat(vars).containsEntry("textAfter", "abc");
    }

    @Test
    void suggest_handles_cursor_at_end() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        service.suggest("abc", 3, 3, null, TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        Map<String, Object> vars = captor.getValue().getPebbleVars();
        assertThat(vars).containsEntry("textBefore", "abc");
        assertThat(vars).containsEntry("textAfter", "");
    }

    @Test
    void suggest_clamps_out_of_range_cursor() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        // Cursor beyond text length — clamp to text.length()
        service.suggest("abc", 999, 3, null, TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        Map<String, Object> vars = captor.getValue().getPebbleVars();
        assertThat(vars).containsEntry("textBefore", "abc");
        assertThat(vars).containsEntry("textAfter", "");
    }

    // ── Reply mode (cursor == null) ────────────────────────────────

    @Test
    void suggest_reply_mode_passes_text_as_precedingContext() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        service.suggest("Hi, how can I help?", null, 3, "chat-reply", TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        Map<String, Object> vars = captor.getValue().getPebbleVars();
        assertThat(vars).containsEntry("precedingContext", "Hi, how can I help?");
        assertThat(vars).doesNotContainKey("textBefore");
        assertThat(vars).doesNotContainKey("textAfter");
    }

    @Test
    void suggest_edit_mode_does_not_pass_precedingContext() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        service.suggest("abc", 1, 3, null, TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        Map<String, Object> vars = captor.getValue().getPebbleVars();
        assertThat(vars).containsEntry("textBefore", "a");
        assertThat(vars).containsEntry("textAfter", "bc");
        assertThat(vars).doesNotContainKey("precedingContext");
    }

    // ── Count clamping + truncation ────────────────────────────────

    @Test
    void suggest_clamps_count_to_max() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        service.suggest("x", 0, 999, null, TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        assertThat(captor.getValue().getPebbleVars()).containsEntry("count", FollowUpService.MAX_COUNT);
    }

    @Test
    void suggest_truncates_when_llm_returns_more_than_requested() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of(
                        Map.of("text", "first"),
                        Map.of("text", "second"),
                        Map.of("text", "third"),
                        Map.of("text", "fourth"))));

        List<FollowUpSuggestionDto> out = service.suggest("x", 0, 2, null, TENANT, PROJECT);

        assertThat(out).hasSize(2);
        assertThat(out).extracting(FollowUpSuggestionDto::getText)
                .containsExactly("first", "second");
    }

    // ── Suggestion parsing ─────────────────────────────────────────

    @Test
    void suggest_returns_typed_dtos_with_kind() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of(
                        Map.of("text", "Ask about deadlines?", "kind", "question"),
                        Map.of("text", "Continue: ...", "kind", "continuation"))));

        List<FollowUpSuggestionDto> out = service.suggest("Hello", 5, 3, null, TENANT, PROJECT);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getText()).isEqualTo("Ask about deadlines?");
        assertThat(out.get(0).getKind()).isEqualTo("question");
        assertThat(out.get(1).getKind()).isEqualTo("continuation");
    }

    @Test
    void suggest_accepts_bare_strings_in_array() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of("first", "second")));

        List<FollowUpSuggestionDto> out = service.suggest("x", 0, 3, null, TENANT, PROJECT);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getText()).isEqualTo("first");
        assertThat(out.get(0).getKind()).isNull();
    }

    @Test
    void suggest_drops_entries_with_blank_or_missing_text() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of(
                        Map.of("text", "ok"),
                        Map.of("text", ""),                 // blank text
                        Map.of("kind", "question"),         // no text field
                        Map.of("text", "  "),               // whitespace only
                        "")));                              // empty string

        List<FollowUpSuggestionDto> out = service.suggest("x", 0, 5, null, TENANT, PROJECT);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getText()).isEqualTo("ok");
    }

    @Test
    void suggest_returns_empty_when_suggestions_key_missing() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of());

        List<FollowUpSuggestionDto> out = service.suggest("x", 0, 3, null, TENANT, PROJECT);

        assertThat(out).isEmpty();
    }

    @Test
    void suggest_returns_empty_when_suggestions_empty_array() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        List<FollowUpSuggestionDto> out = service.suggest("x", 0, 3, null, TENANT, PROJECT);

        assertThat(out).isEmpty();
    }

    // ── Mode passthrough ───────────────────────────────────────────

    @Test
    void suggest_passes_mode_when_set() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        service.suggest("x", 0, 3, "chat-prompt", TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        assertThat(captor.getValue().getPebbleVars()).containsEntry("mode", "chat-prompt");
    }

    @Test
    void suggest_omits_mode_when_null_or_blank() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        service.suggest("x", 0, 3, "   ", TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        // Recipe template falls back to "generic" via Pebble's {% if mode %} branch
        // when the variable isn't present.
        assertThat(captor.getValue().getPebbleVars()).doesNotContainKey("mode");
    }

    // ── Light-LLM request wiring ───────────────────────────────────

    @Test
    void suggest_uses_followup_recipe_and_passes_scope() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of()));

        service.suggest("x", 0, 3, null, TENANT, PROJECT);

        ArgumentCaptor<LightLlmRequest> captor = forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(captor.capture());
        LightLlmRequest req = captor.getValue();
        assertThat(req.getRecipeName()).isEqualTo("follow-up");
        assertThat(req.getTenantId()).isEqualTo(TENANT);
        assertThat(req.getProjectId()).isEqualTo(PROJECT);
        assertThat(req.getSchema()).isNotNull();
    }

    // ── Validation ─────────────────────────────────────────────────

    @Test
    void suggest_rejects_null_text() {
        assertThatThrownBy(() -> service.suggest(null, 0, 3, null, TENANT, PROJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void suggest_rejects_blank_tenant() {
        assertThatThrownBy(() -> service.suggest("x", 0, 3, null, "  ", PROJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    // ── Cache ──────────────────────────────────────────────────────

    @Test
    void suggest_serves_repeated_call_from_cache() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of(Map.of("text", "one"))));

        List<FollowUpSuggestionDto> first =
                service.suggest("Hello", null, 1, "chat-reply", TENANT, PROJECT);
        List<FollowUpSuggestionDto> second =
                service.suggest("Hello", null, 1, "chat-reply", TENANT, PROJECT);

        assertThat(first).extracting(FollowUpSuggestionDto::getText).containsExactly("one");
        assertThat(second).extracting(FollowUpSuggestionDto::getText).containsExactly("one");
        // Second call was a cache hit — only one LLM round-trip.
        verify(lightLlm, Mockito.times(1)).callForJson(any(LightLlmRequest.class));
    }

    @Test
    void suggest_cache_differentiates_by_cursor_presence() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of(Map.of("text", "ok"))));

        // Same text + count + mode, but different mode flag (reply vs.
        // edit at offset 0). Each variant must hit the LLM independently.
        service.suggest("Hello", null, 1, "x", TENANT, PROJECT);
        service.suggest("Hello", 0, 1, "x", TENANT, PROJECT);

        verify(lightLlm, Mockito.times(2)).callForJson(any(LightLlmRequest.class));
    }

    @Test
    void suggest_cache_differentiates_by_tenant() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of(Map.of("text", "ok"))));

        service.suggest("Hello", null, 1, "chat-reply", "tenant-a", PROJECT);
        service.suggest("Hello", null, 1, "chat-reply", "tenant-b", PROJECT);

        verify(lightLlm, Mockito.times(2)).callForJson(any(LightLlmRequest.class));
    }

    @Test
    void suggest_cache_differentiates_by_project() {
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of(Map.of("text", "ok"))));

        service.suggest("Hello", null, 1, "chat-reply", TENANT, "proj-a");
        service.suggest("Hello", null, 1, "chat-reply", TENANT, "proj-b");

        verify(lightLlm, Mockito.times(2)).callForJson(any(LightLlmRequest.class));
    }

    @Test
    void suggest_cache_records_hit_and_miss_metrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FollowUpService scoped = new FollowUpService(lightLlm, new MetricService(registry));
        when(lightLlm.callForJson(any(LightLlmRequest.class))).thenReturn(Map.of(
                "suggestions", List.of(Map.of("text", "x"))));

        scoped.suggest("Hello", null, 1, "chat-reply", TENANT, PROJECT);
        scoped.suggest("Hello", null, 1, "chat-reply", TENANT, PROJECT);
        scoped.suggest("Hello", null, 1, "chat-reply", TENANT, PROJECT);

        double miss = registry.counter("vance.followup.cache", "outcome", "miss").count();
        double hit = registry.counter("vance.followup.cache", "outcome", "hit").count();
        assertThat(miss).isEqualTo(1.0);
        assertThat(hit).isEqualTo(2.0);
    }
}
