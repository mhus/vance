package de.mhus.vance.brain.tools.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.hactar.HactarEngine;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link HactarRunTool}. Verifies the param shape
 * forwarded to {@link ProcessCreateTool} — the tool itself is mocked,
 * so we exercise the wrapper logic only.
 */
class HactarRunToolTest {

    private ProcessCreateTool processCreate;
    private HactarRunTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        processCreate = mock(ProcessCreateTool.class);
        ObjectProvider<ProcessCreateTool> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(processCreate);
        tool = new HactarRunTool(provider);
        ctx = new ToolInvocationContext("acme", "proj-1", "sess-1",
                "proc-1", "u-1");
        when(processCreate.invoke(any(), any())).thenReturn(Map.of(
                "thinkProcessId", "child-1"));
    }

    @Test
    void invoke_buildsHactarRunParamsFromScriptRefOnly() {
        Map<String, Object> result = tool.invoke(
                Map.of("scriptRef", "scripts/mailbot.js"), ctx);

        assertThat(result).containsEntry("thinkProcessId", "child-1");
        Map<String, Object> forwarded = capturedProcessCreateParams();
        assertThat(forwarded).containsEntry("recipe", "hactar-run");
        assertThat(forwarded).containsEntry("goal", "Run script scripts/mailbot.js");
        assertThat(forwarded.get("name")).asString().startsWith("hactar-mailbot-");
        @SuppressWarnings("unchecked")
        Map<String, Object> engineParams = (Map<String, Object>) forwarded.get("params");
        assertThat(engineParams).containsEntry(
                HactarEngine.SCRIPT_REF_KEY, "scripts/mailbot.js");
        assertThat(engineParams).doesNotContainKey(HactarEngine.VALIDATE_BEFORE_RUN_KEY);
    }

    @Test
    void invoke_forwardsOptionalParams() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("scriptRef", "scripts/x.js");
        in.put("validateBeforeRun", true);
        in.put("scriptAllowedTools", List.of("doc_create", "doc_read"));
        in.put("scriptParams", Map.of("k", "v"));
        in.put("timeout", 600);

        tool.invoke(in, ctx);

        @SuppressWarnings("unchecked")
        Map<String, Object> engineParams = (Map<String, Object>)
                capturedProcessCreateParams().get("params");
        assertThat(engineParams)
                .containsEntry(HactarEngine.VALIDATE_BEFORE_RUN_KEY, Boolean.TRUE)
                .containsEntry(HactarEngine.SCRIPT_ALLOWED_TOOLS_KEY,
                        List.of("doc_create", "doc_read"))
                .containsEntry(HactarEngine.SCRIPT_PARAMS_KEY, Map.of("k", "v"))
                .containsEntry(HactarEngine.TIMEOUT_KEY, 600);
    }

    @Test
    void invoke_acceptsCallerSuppliedName() {
        tool.invoke(Map.of(
                "scriptRef", "scripts/x.js",
                "name", "mailbot-2026-06-16-08"), ctx);

        assertThat(capturedProcessCreateParams())
                .containsEntry("name", "mailbot-2026-06-16-08");
    }

    @Test
    void invoke_rejectsMissingScriptRef() {
        assertThatThrownBy(() -> tool.invoke(Map.of(), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("scriptRef");
    }

    @Test
    void invoke_derivedNameSanitizesPath() {
        tool.invoke(Map.of(
                "scriptRef", "scripts/sub-dir/mail.bot.js"), ctx);

        // Basename without extension, lowercase + hyphenated, + uuid suffix.
        assertThat(capturedProcessCreateParams().get("name").toString())
                .startsWith("hactar-mail.bot-")
                .matches("hactar-mail\\.bot-[a-f0-9]{8}");
    }

    // ──────────────────── helpers ────────────────────

    private Map<String, Object> capturedProcessCreateParams() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(processCreate).invoke(captor.capture(), any());
        return captor.getValue();
    }
}
