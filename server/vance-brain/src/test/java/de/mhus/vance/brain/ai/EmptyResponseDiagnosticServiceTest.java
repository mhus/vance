package de.mhus.vance.brain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.EmptyResponseDiagnosticService.DiagnosticCall;
import de.mhus.vance.brain.fook.FookService;
import de.mhus.vance.brain.fook.SubmissionRequest;
import de.mhus.vance.brain.tools.BuiltInToolSource;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the empty-response diagnostics: the candidate diff
 * (conversation text vs. offered tools array), the origin
 * classification, the Megadodo / Fook event selection and the
 * dedup gate with its re-arm window.
 */
class EmptyResponseDiagnosticServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-10T00:00:00Z");

    private BuiltInToolSource builtIns;
    private MegadodoService megadodo;
    private FookService fook;
    private SettingService settings;

    @BeforeEach
    void setUp() {
        builtIns = mock(BuiltInToolSource.class);
        megadodo = mock(MegadodoService.class);
        fook = mock(FookService.class);
        // The ticket path short-circuits on the master switch — the mock
        // default (false) would silently disable every submission.
        when(fook.isEnabled()).thenReturn(true);
        settings = mock(SettingService.class);
        // Build the inventory first, then stub list(): each tool() call
        // opens its own when() stub, and a stub opened inside another
        // when() call is Mockito's unfinished-stubbing trap.
        List<Tool> inventory = List.of(tool("doc_read"), tool("doc_write"), tool("doc_write_lines"));
        when(builtIns.list()).thenReturn(inventory);
    }

    // ──────────────────── fixtures ────────────────────

    private static Tool tool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        return t;
    }

    private static ChatRequest request(
            @Nullable String systemText,
            @Nullable String userText,
            @Nullable String assistantText,
            String... offeredTools) {
        List<dev.langchain4j.data.message.ChatMessage> messages = new java.util.ArrayList<>();
        if (systemText != null) {
            messages.add(SystemMessage.from(systemText));
        }
        if (userText != null) {
            messages.add(UserMessage.from(userText));
        }
        if (assistantText != null) {
            messages.add(AiMessage.from(assistantText));
        }
        ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
        List<ToolSpecification> specs = new java.util.ArrayList<>();
        for (String name : offeredTools) {
            specs.add(ToolSpecification.builder().name(name).build());
        }
        // toolSpecifications(...) is a setter like messages(...) — one
        // call with the full list, or each loop turn replaces the last.
        return builder.toolSpecifications(specs).build();
    }

    private static DiagnosticCall call() {
        return new DiagnosticCall("tenant-a", "project-b", "session-c", "process-d", "arthur");
    }

    private EmptyResponseDiagnosticService service() {
        return service(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EmptyResponseDiagnosticService service(Clock clock) {
        return new EmptyResponseDiagnosticService(builtIns, megadodo, fook, settings, clock);
    }

    // ──────────────────── candidate diff ────────────────────

    @Test
    void phantomNameInSystemMessage_isCandidate_withOriginPrompt() {
        EmptyResponseDiagnosticService.Diagnosis diagnosis =
                service().diagnose(request("Use doc_write to create the report.", "go", null, "doc_read"));

        assertThat(diagnosis.candidates()).containsExactly("doc_write");
        assertThat(diagnosis.origin()).isEqualTo(EmptyResponseDiagnosticService.ORIGIN_PROMPT);
        assertThat(diagnosis.offeredTools()).isEqualTo(1);
    }

    @Test
    void phantomNameInUserMessage_hasOriginUser_overContext() {
        EmptyResponseDiagnosticService.Diagnosis diagnosis = service()
                .diagnose(request("system", "please run doc_write now", "earlier I mentioned doc_read", "doc_read"));

        assertThat(diagnosis.candidates()).containsExactly("doc_write");
        // A user-originated name is the actionable variant: the user asked
        // for a tool this surface does not offer.
        assertThat(diagnosis.origin()).isEqualTo(EmptyResponseDiagnosticService.ORIGIN_USER);
    }

    @Test
    void phantomNameOnlyInHistory_hasOriginContext() {
        EmptyResponseDiagnosticService.Diagnosis diagnosis =
                service().diagnose(request("system", "user text", "assistant used doc_write earlier", "doc_read"));

        assertThat(diagnosis.candidates()).containsExactly("doc_write");
        assertThat(diagnosis.origin()).isEqualTo(EmptyResponseDiagnosticService.ORIGIN_CONTEXT);
    }

    @Test
    void offeredToolMentionedInConversation_isNoCandidate() {
        EmptyResponseDiagnosticService.Diagnosis diagnosis = service()
                .diagnose(
                        request("system mentions doc_write and doc_read", "user text", null, "doc_read", "doc_write"));

        assertThat(diagnosis.candidates()).isEmpty();
    }

    @Test
    void longerToolNameDoesNotMatchAsSubstring() {
        // "doc_write_lines" is a distinct token — it must not make
        // "doc_write" a candidate by substring, nor the other way round.
        EmptyResponseDiagnosticService.Diagnosis diagnosis =
                service().diagnose(request("use doc_write_lines", "user text", null, "doc_write"));

        assertThat(diagnosis.candidates()).containsExactly("doc_write_lines");
    }

    @Test
    void uppercaseVariantIsNormalized_notProse() {
        // Token collection lowercases: users write tool names in any
        // caps form ("Doc_Write please"), and a name is a name — the
        // uppercase spelling does not hide a phantom candidate.
        EmptyResponseDiagnosticService.Diagnosis diagnosis =
                service().diagnose(request("The DOC_WRITE section of the manual", "user text", null, "doc_read"));

        assertThat(diagnosis.candidates()).containsExactly("doc_write");
    }

    // ──────────────────── event selection ────────────────────

    @Test
    void phantomCase_firesMegadodo_andGatedFookTicket() {
        when(settings.getStringValueCascade(anyString(), any(), any(), anyString()))
                .thenReturn(null);
        when(fook.submit(any())).thenReturn("sub-1");

        service()
                .onEmptyResponseExhausted(
                        call(), request("use doc_write", "user text", null, "doc_read"), "glm-5.3", 3);

        verify(megadodo)
                .phantomToolCallSuspected(
                        eq("tenant-a"),
                        eq("project-b"),
                        eq("process-d"),
                        eq("glm-5.3"),
                        eq(List.of("doc_write")),
                        eq(EmptyResponseDiagnosticService.ORIGIN_PROMPT),
                        eq(3));

        ArgumentCaptor<SubmissionRequest> submitted = ArgumentCaptor.forClass(SubmissionRequest.class);
        verify(fook).submit(submitted.capture());
        assertThat(submitted.getValue().getText())
                .contains("doc_write")
                .contains("glm-5.3")
                .contains("origin: prompt");
    }

    @Test
    void fookDisabled_firesMegadodo_butNeitherGateNorSubmit() {
        // The master switch short-circuits before the gate: no marker
        // burn (which would silence the signature for the whole re-arm
        // window), no submit (which would throw — reporting surfaces
        // short-circuit, the exception is defense-in-depth).
        when(fook.isEnabled()).thenReturn(false);

        service()
                .onEmptyResponseExhausted(
                        call(), request("use doc_write", "user text", null, "doc_read"), "glm-5.3", 3);

        verify(megadodo)
                .phantomToolCallSuspected(anyString(), any(), any(), anyString(), anyList(), anyString(), anyInt());
        verify(fook, never()).submit(any());
        verify(settings, never()).setStringValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void blankCase_firesMegadodoOnly_neverFook() {
        service()
                .onEmptyResponseExhausted(
                        call(), request("clean system text", "clean user text", null, "doc_read"), "glm-5.3", 2);

        verify(megadodo).emptyModelResponse(eq("tenant-a"), eq("project-b"), eq("process-d"), eq("glm-5.3"), eq(2));
        verify(fook, never()).submit(any());
    }

    @Test
    void aThrowingMegadodo_doesNotBreakTheReportingCall() {
        doThrow(new RuntimeException("mongo down"))
                .when(megadodo)
                .phantomToolCallSuspected(anyString(), any(), any(), anyString(), anyList(), anyString(), anyInt());

        assertThatCode(() -> service()
                        .onEmptyResponseExhausted(
                                call(), request("use doc_write", "user text", null, "doc_read"), "glm-5.3", 3))
                .doesNotThrowAnyException();
    }

    // ──────────────────── dedup gate ────────────────────

    @Test
    void gate_allowsFirstReport_andBlocksRepeatsWithinTheWindow() {
        when(settings.getStringValueCascade(anyString(), any(), any(), anyString()))
                .thenReturn(null, NOW.toString());

        EmptyResponseDiagnosticService fixedNow = service();

        assertThat(fixedNow.gateAllows("tenant-a", "glm-5.3", List.of("doc_write")))
                .isTrue();
        assertThat(fixedNow.gateAllows("tenant-a", "glm-5.3", List.of("doc_write")))
                .isFalse();

        // The marker is a tenant-scope row keyed by the signature digest.
        verify(settings)
                .setStringValue(
                        eq("tenant-a"),
                        eq(SettingService.SCOPE_PROJECT),
                        eq(HomeBootstrapService.TENANT_PROJECT_NAME),
                        eq(EmptyResponseDiagnosticService.SETTING_REPORTED_PREFIX
                                + EmptyResponseDiagnosticService.signature("glm-5.3", List.of("doc_write"))),
                        eq(NOW.toString()));
    }

    @Test
    void gate_rearmsAfterTheWindow() {
        // Reported thirteen days ago, window is the 14-day default
        // (the tenant sets nothing) — still blocked. Exactly fourteen
        // would sit on the boundary, where plus(window) == now and
        // isAfter() tips to re-armed.
        when(settings.getStringValueCascade(anyString(), any(), any(), anyString()))
                .thenReturn(Instant.parse("2025-12-28T00:00:00Z").toString());

        assertThat(service(Clock.fixed(NOW, ZoneOffset.UTC)).gateAllows("tenant-a", "glm-5.3", List.of("doc_write")))
                .isFalse();

        // Fifteen days after the first report — re-armed.
        when(settings.getStringValueCascade(anyString(), any(), any(), anyString()))
                .thenReturn(Instant.parse("2025-12-26T00:00:00Z").toString());
        assertThat(service(Clock.fixed(NOW, ZoneOffset.UTC)).gateAllows("tenant-a", "glm-5.3", List.of("doc_write")))
                .isTrue();
    }

    @Test
    void gate_rearmsImmediatelyOnGarbageMarker() {
        when(settings.getStringValueCascade(anyString(), any(), any(), anyString()))
                .thenReturn("not-an-instant");

        assertThat(service().gateAllows("tenant-a", "glm-5.3", List.of("doc_write")))
                .isTrue();
    }

    @Test
    void gate_zeroReArmDays_reportsEveryOccurrence() {
        // Marker says "reported now", but the tenant dial says window=0.
        when(settings.getStringValueCascade(
                        anyString(), any(), any(), eq(EmptyResponseDiagnosticService.SETTING_RE_ARM_DAYS)))
                .thenReturn("0");
        when(settings.getStringValueCascade(
                        anyString(), any(), any(), contains(EmptyResponseDiagnosticService.SETTING_REPORTED_PREFIX)))
                .thenReturn(NOW.toString());

        assertThat(service().gateAllows("tenant-a", "glm-5.3", List.of("doc_write")))
                .isTrue();
    }

    @Test
    void gate_invalidReArmDaysSetting_fallsBackToDefault() {
        when(settings.getStringValueCascade(
                        anyString(), any(), any(), eq(EmptyResponseDiagnosticService.SETTING_RE_ARM_DAYS)))
                .thenReturn("soon-ish");
        when(settings.getStringValueCascade(
                        anyString(), any(), any(), contains(EmptyResponseDiagnosticService.SETTING_REPORTED_PREFIX)))
                .thenReturn(NOW.minus(Duration.ofDays(14)).toString());

        // Marker is exactly DEFAULT_RE_ARM_DAYS old → outside the window.
        assertThat(service().gateAllows("tenant-a", "glm-5.3", List.of("doc_write")))
                .isTrue();
    }

    @Test
    void signature_isStableAndBounded() {
        String a = EmptyResponseDiagnosticService.signature("glm-5.3", List.of("doc_write"));
        String b = EmptyResponseDiagnosticService.signature("glm-5.3", List.of("doc_write"));
        String other = EmptyResponseDiagnosticService.signature("glm-5.3", List.of("doc_write_lines"));

        assertThat(a).isEqualTo(b).hasSize(22);
        assertThat(a).isNotEqualTo(other);
    }
}
