package de.mhus.vance.brain.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.session.SessionColor;
import de.mhus.vance.brain.ai.light.LightLlmException;
import de.mhus.vance.brain.ai.light.LightLlmRequest;
import de.mhus.vance.brain.ai.light.LightLlmService;
import de.mhus.vance.brain.ai.light.SchemaValidationException;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SessionMetadataSuggesterTest {

    private LightLlmService lightLlm;
    private ChatMessageService chatMessageService;
    private SessionService sessionService;
    private SessionMetadataSuggester suggester;

    @BeforeEach
    void setUp() {
        lightLlm = mock(LightLlmService.class);
        chatMessageService = mock(ChatMessageService.class);
        sessionService = mock(SessionService.class);
        suggester = new SessionMetadataSuggester(lightLlm, chatMessageService, sessionService);
        ReflectionTestUtils.setField(suggester, "enabled", true);
    }

    @Test
    void suggest_persistsTitleIconColor() {
        SessionDocument session = freshSession();
        when(chatMessageService.openingWindow(
                eq("acme"), eq("s-1"), any(), anyInt()))
                .thenReturn(List.of(
                        msg(ChatRole.USER, "Debugging the JWT rotation bug"),
                        msg(ChatRole.ASSISTANT, "Looking at the auth middleware now…")));
        when(lightLlm.callForJson(any())).thenReturn(reply(
                "JWT rotation debugging", "🐛", "AMBER"));

        suggester.suggest(session);

        verify(sessionService).applyAutoSuggestedMetadata(
                "s-1", "JWT rotation debugging", "🐛", SessionColor.AMBER);
    }

    @Test
    void suggest_passesOpeningAsPebbleVar() {
        SessionDocument session = freshSession();
        when(chatMessageService.openingWindow(
                eq("acme"), eq("s-1"), any(), anyInt()))
                .thenReturn(List.of(msg(ChatRole.USER, "Topic seed")));
        when(lightLlm.callForJson(any())).thenReturn(reply("T", "🎯", "BLUE"));

        suggester.suggest(session);

        ArgumentCaptor<LightLlmRequest> cap = ArgumentCaptor.forClass(LightLlmRequest.class);
        verify(lightLlm).callForJson(cap.capture());
        LightLlmRequest req = cap.getValue();
        assertThat(req.getRecipeName()).isEqualTo(SessionMetadataSuggester.RECIPE_NAME);
        assertThat(req.getTenantId()).isEqualTo("acme");
        assertThat(req.getProjectId()).isEqualTo("lit-review");
        assertThat(req.getPebbleVars()).containsKey("opening");
        String opening = (String) req.getPebbleVars().get("opening");
        assertThat(opening).contains("[user] Topic seed");
    }

    @Test
    void suggest_unknownColor_keepsOtherFields() {
        SessionDocument session = freshSession();
        when(chatMessageService.openingWindow(any(), any(), any(), anyInt()))
                .thenReturn(List.of(msg(ChatRole.USER, "x")));
        when(lightLlm.callForJson(any())).thenReturn(reply("Title", "🎯", "FUCHSIA"));

        suggester.suggest(session);

        // Color falls through as null; title + icon still applied.
        verify(sessionService).applyAutoSuggestedMetadata("s-1", "Title", "🎯", null);
    }

    @Test
    void suggest_disabled_isNoop() {
        ReflectionTestUtils.setField(suggester, "enabled", false);

        suggester.suggest(freshSession());

        verify(lightLlm, never()).callForJson(any());
        verify(sessionService, never()).applyAutoSuggestedMetadata(any(), any(), any(), any());
    }

    @Test
    void suggest_allMetadataFilled_skips() {
        SessionDocument session = freshSession();
        session.setTitle("manual");
        session.setIcon("📌");
        session.setColor(SessionColor.SLATE);

        suggester.suggest(session);

        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void suggest_userTouched_skips() {
        SessionDocument session = freshSession();
        session.setUserTouchedAt(Instant.now());

        suggester.suggest(session);

        verify(lightLlm, never()).callForJson(any());
    }

    @Test
    void suggest_emptyOpening_skipsLlmAndPersist() {
        SessionDocument session = freshSession();
        when(chatMessageService.openingWindow(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        suggester.suggest(session);

        verify(lightLlm, never()).callForJson(any());
        verify(sessionService, never()).applyAutoSuggestedMetadata(any(), any(), any(), any());
    }

    @Test
    void suggest_lightLlmFailure_isSwallowed() {
        SessionDocument session = freshSession();
        when(chatMessageService.openingWindow(any(), any(), any(), anyInt()))
                .thenReturn(List.of(msg(ChatRole.USER, "x")));
        when(lightLlm.callForJson(any()))
                .thenThrow(new LightLlmException("provider 503"));

        // Best-effort — must not throw.
        suggester.suggest(session);

        verify(sessionService, never()).applyAutoSuggestedMetadata(any(), any(), any(), any());
    }

    @Test
    void suggest_schemaBudgetExhausted_isSwallowed() {
        SessionDocument session = freshSession();
        when(chatMessageService.openingWindow(any(), any(), any(), anyInt()))
                .thenReturn(List.of(msg(ChatRole.USER, "x")));
        when(lightLlm.callForJson(any()))
                .thenThrow(new SchemaValidationException(2, Map.of(), "missing 'title'"));

        suggester.suggest(session);

        verify(sessionService, never()).applyAutoSuggestedMetadata(any(), any(), any(), any());
    }

    @Test
    void suggest_allFieldsMissing_doesNotPersist() {
        SessionDocument session = freshSession();
        when(chatMessageService.openingWindow(any(), any(), any(), anyInt()))
                .thenReturn(List.of(msg(ChatRole.USER, "x")));
        when(lightLlm.callForJson(any())).thenReturn(reply("", "", ""));

        suggester.suggest(session);

        verify(sessionService, never()).applyAutoSuggestedMetadata(any(), any(), any(), any());
    }

    // ──────────────────── helpers ────────────────────

    private static SessionDocument freshSession() {
        SessionDocument s = new SessionDocument();
        s.setTenantId("acme");
        s.setProjectId("lit-review");
        s.setSessionId("s-1");
        return s;
    }

    private static ChatMessageDocument msg(ChatRole role, String content) {
        ChatMessageDocument m = new ChatMessageDocument();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private static Map<String, Object> reply(String title, String icon, String color) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(SessionMetadataSuggester.FIELD_TITLE, title);
        m.put(SessionMetadataSuggester.FIELD_ICON, icon);
        m.put(SessionMetadataSuggester.FIELD_COLOR, color);
        return m;
    }
}
