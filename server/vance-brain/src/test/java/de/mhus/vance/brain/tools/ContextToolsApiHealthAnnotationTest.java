package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.toolhealth.ToolHealthStatus;
import de.mhus.vance.shared.toolhealth.ToolHealthDocument;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the manifest-annotation surface added in
 * {@link ContextToolsApi#annotateDescription}: DOWN/DEGRADED tools get
 * a suffix; expectedRecoveryAt in the past hides the suffix so the LLM
 * retries naively; OK tools and the no-health-service path are
 * pass-through.
 */
class ContextToolsApiHealthAnnotationTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "proj-1";
    private static final String SESSION = "sess-1";
    private static final String USER = "alice";

    private ToolHealthService healthService;
    private Tool tool;
    private ContextToolsApi api;

    @BeforeEach
    void setUp() {
        healthService = mock(ToolHealthService.class);
        tool = mock(Tool.class);
        when(tool.name()).thenReturn("mcp_search");
        when(tool.description()).thenReturn("Search the indexed corpus.");

        api = new ContextToolsApi(
                mock(ToolDispatcher.class),
                new ToolInvocationContext(TENANT, PROJECT, SESSION, "proc-1", USER),
                java.util.Set.of(),               // allowed
                java.util.Set.of(),               // primary
                java.util.Set.of(),               // deferred
                java.util.Set.of(),               // activated
                ToolInvocationListener.NOOP,
                null,                              // refresh
                null,                              // historyTagBuilder
                null,                              // historyTagSink
                null,                              // toolResultStorage
                healthService);
    }

    @Test
    void annotateDescription_okStatus_returnsBaseUnchanged() {
        when(healthService.lookup(eq(TENANT), eq(SESSION), eq(USER),
                eq(PROJECT), eq("mcp_search")))
                .thenReturn(Optional.of(doc(ToolHealthStatus.OK, null, "all good")));

        String out = api.annotateDescription(tool, Instant.now());

        assertThat(out).isEqualTo("Search the indexed corpus.");
    }

    @Test
    void annotateDescription_emptyLookup_returnsBaseUnchanged() {
        when(healthService.lookup(anyString(), any(), any(), any(), anyString()))
                .thenReturn(Optional.empty());

        String out = api.annotateDescription(tool, Instant.now());

        assertThat(out).isEqualTo("Search the indexed corpus.");
    }

    @Test
    void annotateDescription_downWithFutureRecovery_suffixesEta() {
        Instant now = Instant.parse("2026-05-23T12:00:00Z");
        Instant eta = Instant.parse("2026-05-23T12:30:00Z");
        ToolHealthDocument d = doc(ToolHealthStatus.DOWN, eta, "MCP returning 502");
        when(healthService.lookup(anyString(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(d));

        String out = api.annotateDescription(tool, now);

        assertThat(out).contains("Search the indexed corpus.");
        assertThat(out).contains("⚠ Currently unavailable");
        assertThat(out).contains("expected back at 12:30 UTC");
        assertThat(out).contains("MCP returning 502");
    }

    @Test
    void annotateDescription_downWithPassedRecovery_isPassThrough() {
        Instant now = Instant.parse("2026-05-23T12:00:00Z");
        Instant pastEta = Instant.parse("2026-05-23T11:30:00Z");
        when(healthService.lookup(anyString(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(doc(ToolHealthStatus.DOWN, pastEta, "x")));

        String out = api.annotateDescription(tool, now);

        // expectedRecoveryAt in the past → implicit RETESTING; no suffix.
        assertThat(out).isEqualTo("Search the indexed corpus.");
    }

    @Test
    void annotateDescription_downWithNoEta_suffixesSince() {
        Instant now = Instant.parse("2026-05-23T12:00:00Z");
        ToolHealthDocument d = doc(ToolHealthStatus.DOWN, null, null);
        d.setSince(Instant.parse("2026-05-23T11:45:00Z"));
        when(healthService.lookup(anyString(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(d));

        String out = api.annotateDescription(tool, now);

        assertThat(out).contains("⚠ Currently unavailable");
        assertThat(out).contains("since 11:45 UTC");
    }

    @Test
    void annotateDescription_degraded_emitsIntermittentSuffix() {
        Instant now = Instant.parse("2026-05-23T12:00:00Z");
        Instant eta = Instant.parse("2026-05-23T12:05:00Z");
        ToolHealthDocument d = doc(ToolHealthStatus.DEGRADED, eta, "rate-limited");
        when(healthService.lookup(anyString(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(d));

        String out = api.annotateDescription(tool, now);

        assertThat(out).contains("⚠ Intermittent");
        assertThat(out).contains("rate-limited");
    }

    @Test
    void annotateDescription_lookupRuntimeError_isPassThrough() {
        when(healthService.lookup(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("mongo dropped"));

        String out = api.annotateDescription(tool, Instant.now());

        assertThat(out).isEqualTo("Search the indexed corpus.");
    }

    @Test
    void annotateDescription_noHealthService_isPassThrough() {
        ContextToolsApi noHealth = new ContextToolsApi(
                mock(ToolDispatcher.class),
                new ToolInvocationContext(TENANT, PROJECT, SESSION, "proc-1", USER),
                java.util.Set.of());

        String out = noHealth.annotateDescription(tool, Instant.now());

        assertThat(out).isEqualTo("Search the indexed corpus.");
    }

    private static ToolHealthDocument doc(
            ToolHealthStatus status,
            java.time.Instant eta,
            String note) {
        return ToolHealthDocument.builder()
                .tenantId(TENANT)
                .toolName("mcp_search")
                .status(status)
                .expectedRecoveryAt(eta)
                .lastNote(note)
                .build();
    }
}
