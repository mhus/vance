package de.mhus.vance.brain.agrajag.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Focused tests for the deterministic Agrajag output→write translation
 * and the LLM-output parsing helpers. The actual LLM call is out of
 * scope for unit tests; we drive {@code applyForTest} with pre-built
 * output maps.
 */
class AgrajagEngineTest {

    private ToolHealthService healthService;
    private AgrajagEngine engine;
    private ThinkProcessDocument process;

    @BeforeEach
    void setUp() {
        healthService = mock(ToolHealthService.class);
        engine = new AgrajagEngine(
                mock(ThinkProcessService.class),
                healthService,
                mock(EngineChatFactory.class),
                new ObjectMapper());
        process = ThinkProcessDocument.builder()
                .id("proc-1")
                .tenantId("acme")
                .projectId("proj-1")
                .sessionId("sess-fook")
                .name("diagnose-tool-x")
                .thinkEngine(AgrajagEngine.NAME)
                .build();
    }

    @Test
    void apply_technicallyBroken_writesUnavailableOnProjectScope() {
        Map<String, Object> output = Map.of(
                "classification", "TECHNICALLY_BROKEN",
                "expectedRecoveryAt", "2026-05-23T15:30:00Z",
                "humanNote", "MCP gateway 502s since 14:00");

        engine.applyForTest(process, ToolHealthScope.PROJECT, "proj-1",
                "mcp_search", output);

        verify(healthService).markUnavailable(
                eq("acme"),
                eq(ToolHealthScope.PROJECT),
                eq("proj-1"),
                eq("mcp_search"),
                eq(ToolHealthClassification.TECHNICALLY_BROKEN),
                eq(Instant.parse("2026-05-23T15:30:00Z")),
                eq("MCP gateway 502s since 14:00"),
                anyString());
        verify(healthService, never()).markDegraded(
                anyString(), any(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void apply_userSpecificTechnical_writesUnavailableOnUserScope() {
        Map<String, Object> output = Map.of(
                "classification", "USER_SPECIFIC_TECHNICAL",
                "expectedRecoveryAt", "2026-05-23T16:00:00Z",
                "humanNote", "Alice's Atlassian token expired");

        engine.applyForTest(process, ToolHealthScope.USER, "alice",
                "jira_create_issue", output);

        verify(healthService).markUnavailable(
                eq("acme"),
                eq(ToolHealthScope.USER),
                eq("alice"),
                eq("jira_create_issue"),
                eq(ToolHealthClassification.USER_SPECIFIC_TECHNICAL),
                any(),
                anyString(),
                anyString());
    }

    @Test
    void apply_intermittent_writesDegraded() {
        Map<String, Object> output = Map.of(
                "classification", "INTERMITTENT",
                "expectedRecoveryAt", "2026-05-23T15:05:00Z",
                "humanNote", "Sporadic 503s — likely rate-limited");

        engine.applyForTest(process, ToolHealthScope.PROJECT, "proj-1",
                "tool_x", output);

        verify(healthService).markDegraded(
                eq("acme"), eq(ToolHealthScope.PROJECT), eq("proj-1"),
                eq("tool_x"),
                eq(ToolHealthClassification.INTERMITTENT),
                any(), anyString(), anyString());
        verify(healthService, never()).markUnavailable(
                anyString(), any(), anyString(), anyString(), any(), any(), any(), anyString());
    }

    @Test
    void apply_working_writesAvailableClearingPrevious() {
        Map<String, Object> output = Map.of(
                "classification", "WORKING",
                "humanNote", "Probe succeeded; tool is back");

        engine.applyForTest(process, ToolHealthScope.PROJECT, "proj-1",
                "tool_x", output);

        verify(healthService).markAvailable(
                eq("acme"), eq(ToolHealthScope.PROJECT), eq("proj-1"),
                eq("tool_x"),
                anyString(), anyString());
    }

    @Test
    void apply_userPermission_writesNoHealthEntry_butStillAdjustsCooldowns() {
        Map<String, Object> output = Map.of(
                "classification", "USER_PERMISSION",
                "humanNote", "User lacks write scope",
                "cooldownAdjustments", List.of(Map.of(
                        "errorSignature", "http-403",
                        "duration", "PT24H",
                        "userId", "alice")));

        engine.applyForTest(process, ToolHealthScope.USER, "alice",
                "jira_create_issue", output);

        verify(healthService, never()).markUnavailable(
                anyString(), any(), anyString(), anyString(), any(), any(), any(), anyString());
        verify(healthService, never()).markDegraded(
                anyString(), any(), anyString(), anyString(), any(), any(), any(), anyString());
        verify(healthService, never()).markAvailable(
                anyString(), any(), anyString(), anyString(), any(), anyString());

        verify(healthService).setCooldown(
                eq("acme"), eq(ToolHealthScope.USER), eq("alice"),
                eq("jira_create_issue"),
                eq("http-403"), eq("alice"),
                eq(ToolHealthClassification.USER_PERMISSION),
                eq(Duration.ofHours(24)),
                anyString());
    }

    @Test
    void apply_unclear_fallbackToDegradedWithDefaultEta() {
        Map<String, Object> output = Map.of(
                "classification", "UNCLEAR",
                "humanNote", "Evidence too thin");

        engine.applyForTest(process, ToolHealthScope.PROJECT, "proj-1",
                "tool_x", output);

        verify(healthService).markDegraded(
                eq("acme"), eq(ToolHealthScope.PROJECT), eq("proj-1"),
                eq("tool_x"),
                eq(ToolHealthClassification.UNCLEAR),
                any(),                           // recovery ETA filled by engine fallback
                anyString(), anyString());
    }

    @Test
    void extractJson_unwrapsFencedBlock() {
        String input = """
                Sure, here is the result:
                ```json
                {"classification": "INTERMITTENT", "humanNote": "x"}
                ```
                """;
        String out = AgrajagEngine.extractJson(input);
        assertThat(out).isEqualTo("{\"classification\": \"INTERMITTENT\", \"humanNote\": \"x\"}");
    }

    @Test
    void extractJson_fallsBackToBraceBalancedSlice() {
        String input = "Garbage before {\"classification\":\"WORKING\"} trailing chatter";
        String out = AgrajagEngine.extractJson(input);
        assertThat(out).isEqualTo("{\"classification\":\"WORKING\"}");
    }

    @Test
    void parseClassification_unknown_defaultsToUnclear() {
        assertThat(AgrajagEngine.parseClassification("not-a-real-value"))
                .isEqualTo(ToolHealthClassification.UNCLEAR);
        assertThat(AgrajagEngine.parseClassification(null))
                .isEqualTo(ToolHealthClassification.UNCLEAR);
    }

    @Test
    void parseInstantOrNull_handlesNullAndBadValues() {
        assertThat(AgrajagEngine.parseInstantOrNull(null)).isNull();
        assertThat(AgrajagEngine.parseInstantOrNull("")).isNull();
        assertThat(AgrajagEngine.parseInstantOrNull("garbage")).isNull();
        assertThat(AgrajagEngine.parseInstantOrNull("2026-05-23T15:30:00Z"))
                .isEqualTo(Instant.parse("2026-05-23T15:30:00Z"));
    }
}
