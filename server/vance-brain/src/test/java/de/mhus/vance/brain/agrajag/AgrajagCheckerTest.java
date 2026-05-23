package de.mhus.vance.brain.agrajag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.brain.agrajag.ToolErrorPattern.HealthAction;
import de.mhus.vance.shared.toolhealth.ToolHealthCooldown;
import de.mhus.vance.shared.toolhealth.ToolHealthService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgrajagCheckerTest {

    private ToolErrorPatternResolver resolver;
    private ToolHealthService healthService;
    private AgrajagChecker checker;

    @BeforeEach
    void setUp() {
        resolver = mock(ToolErrorPatternResolver.class);
        healthService = mock(ToolHealthService.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<AgrajagSpawnerService> spawnerProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(spawnerProvider.getIfAvailable()).thenReturn(null);
        checker = new AgrajagChecker(resolver, healthService, spawnerProvider);
        when(healthService.lookupActiveCooldown(
                anyString(), any(), anyString(), anyString(), anyString(),
                any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void handle_http403_setsUserScopeCooldownNoHealthWrite() {
        when(resolver.resolve(anyString(), any()))
                .thenReturn(List.of(http403Pattern()));

        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", "alice");
        ToolException err = new ToolException(
                "Forbidden (HTTP 403) — caller lacks scope");

        AgrajagCheckResult res = checker.handle("jira_create_issue", err, ctx);

        assertThat(res.classification()).isEqualTo(ToolHealthClassification.USER_PERMISSION);
        assertThat(res.signature()).isEqualTo("http-403");
        assertThat(res.cooldownAlreadyActive()).isFalse();
        assertThat(res.wroteHealth()).isFalse();

        verify(healthService).setCooldown(
                eq("acme"), eq(ToolHealthScope.USER), eq("alice"),
                eq("jira_create_issue"), eq("http-403"), eq("alice"),
                eq(ToolHealthClassification.USER_PERMISSION),
                any(Duration.class), any());
        verify(healthService, never()).markUnavailable(
                anyString(), any(), anyString(), anyString(),
                any(), any(), any(), anyString());
    }

    @Test
    void handle_connectRefused_writesHealthDownProjectScope() {
        when(resolver.resolve(anyString(), any()))
                .thenReturn(List.of(connectRefusedPattern()));

        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", "alice");
        ToolException err = new ToolException(
                "MCP connect failed",
                new java.net.ConnectException("Connection refused"));

        AgrajagCheckResult res = checker.handle("mcp_search", err, ctx);

        assertThat(res.classification()).isEqualTo(ToolHealthClassification.TECHNICALLY_BROKEN);
        assertThat(res.wroteHealth()).isTrue();
        verify(healthService).markUnavailable(
                eq("acme"), eq(ToolHealthScope.PROJECT), eq("proj-1"),
                eq("mcp_search"),
                eq(ToolHealthClassification.TECHNICALLY_BROKEN),
                any(), any(), eq("agrajag-checker"));
        verify(healthService, atLeastOnce()).setCooldown(
                anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(Duration.class), any());
    }

    @Test
    void handle_clientToolWithSessionId_usesSessionScope() {
        // Generic UNCLEAR pattern that still chooses scope based on toolName.
        ToolErrorPattern p = ToolErrorPattern.builder()
                .id("unclear").signature("unclear")
                .classification(ToolHealthClassification.TECHNICALLY_BROKEN)
                .healthAction(HealthAction.MARK_UNAVAILABLE)
                .build();
        when(resolver.resolve(anyString(), any())).thenReturn(List.of(p));

        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", "alice");

        checker.handle("client_file_read",
                new ToolException("Client gone"), ctx);

        verify(healthService).markUnavailable(
                eq("acme"), eq(ToolHealthScope.SESSION), eq("sess-1"),
                eq("client_file_read"),
                any(), any(), any(), anyString());
    }

    @Test
    void handle_cooldownAlreadyActive_skipsAllSideEffects() {
        when(resolver.resolve(anyString(), any()))
                .thenReturn(List.of(http403Pattern()));
        when(healthService.lookupActiveCooldown(
                anyString(), any(), anyString(), anyString(),
                eq("http-403"), eq("alice"), any()))
                .thenReturn(Optional.of(ToolHealthCooldown.builder()
                        .errorSignature("http-403").userId("alice")
                        .nextSpawnAllowedAt(Instant.now().plus(Duration.ofHours(1)))
                        .hits(3)
                        .build()));

        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", "alice");

        AgrajagCheckResult res = checker.handle("jira_create_issue",
                new ToolException("Forbidden 403"), ctx);

        assertThat(res.cooldownAlreadyActive()).isTrue();
        verify(healthService, never()).setCooldown(
                anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(Duration.class), any());
        verify(healthService, never()).markUnavailable(
                anyString(), any(), anyString(), anyString(),
                any(), any(), any(), anyString());
    }

    @Test
    void handle_noPatternMatched_returnsUnmatched() {
        when(resolver.resolve(anyString(), any())).thenReturn(List.of());

        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj-1", "sess-1", "proc-1", "alice");

        AgrajagCheckResult res = checker.handle("orphan_tool",
                new ToolException("weird"), ctx);
        assertThat(res.classification()).isEqualTo(ToolHealthClassification.UNCLEAR);
        verify(healthService, never()).setCooldown(
                anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(Duration.class), any());
    }

    @Test
    void matches_httpStatusRange_isInclusive() {
        ToolErrorPattern p = ToolErrorPattern.builder()
                .id("http-5xx")
                .httpStatusRange(new int[]{500, 599})
                .signature("http-5xx")
                .classification(ToolHealthClassification.UNCLEAR)
                .build();
        assertThat(AgrajagChecker.matches(p, 500, List.of("X"), "")).isTrue();
        assertThat(AgrajagChecker.matches(p, 599, List.of("X"), "")).isTrue();
        assertThat(AgrajagChecker.matches(p, 499, List.of("X"), "")).isFalse();
        assertThat(AgrajagChecker.matches(p, 600, List.of("X"), "")).isFalse();
    }

    @Test
    void matches_exceptionTypeOr_isLenient() {
        ToolErrorPattern p = ToolErrorPattern.builder()
                .exceptionTypes(List.of("java.net.SocketTimeoutException",
                                        "java.util.concurrent.TimeoutException"))
                .signature("timeout")
                .classification(ToolHealthClassification.UNCLEAR)
                .build();
        assertThat(AgrajagChecker.matches(
                p, null,
                List.of("de.foo.X", "java.util.concurrent.TimeoutException"),
                "")).isTrue();
        assertThat(AgrajagChecker.matches(p, null, List.of("de.foo.X"), "")).isFalse();
    }

    @Test
    void matches_bodyContains_isCaseInsensitive() {
        ToolErrorPattern p = ToolErrorPattern.builder()
                .bodyContains(List.of("Expired_Token"))
                .signature("auth")
                .classification(ToolHealthClassification.USER_SPECIFIC_TECHNICAL)
                .build();
        assertThat(AgrajagChecker.matches(
                p, null, List.of(),
                "Server returned: {\"error\": \"EXPIRED_TOKEN\"}")).isTrue();
        assertThat(AgrajagChecker.matches(p, null, List.of(), "nope")).isFalse();
    }

    // ──────────────────────────── helpers

    private static ToolErrorPattern http403Pattern() {
        return ToolErrorPattern.builder()
                .id("http-403")
                .httpStatus(403)
                .signature("http-403")
                .classification(ToolHealthClassification.USER_PERMISSION)
                .cooldown(Duration.ofHours(24))
                .locked(true)
                .build();
    }

    private static ToolErrorPattern connectRefusedPattern() {
        return ToolErrorPattern.builder()
                .id("connect-refused")
                .exceptionTypes(List.of("java.net.ConnectException",
                                        "java.net.UnknownHostException"))
                .signature("connect-refused")
                .classification(ToolHealthClassification.TECHNICALLY_BROKEN)
                .healthAction(HealthAction.MARK_UNAVAILABLE)
                .cooldown(Duration.ofMinutes(5))
                .locked(true)
                .build();
    }
}
