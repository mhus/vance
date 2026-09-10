package de.mhus.vance.brain.tools.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.settings.AgentSettingKeyPolicy;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tool is the agent-reachable twin of the admin settings REST, so the
 * tests pin exactly the three guards that keep it from becoming an
 * escalation: ADMIN on the target scope (never a weaker action or
 * resource), W1 (an existing encrypted setting is untouchable), and W3
 * (deny-listed keys refused, with the recipe-granted carve-out covering
 * only {@code type} and {@code baseUrl} of a provider instance — never
 * the credential).
 */
class SettingSetToolTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "model-sipgate-coding";
    private static final String USER = "road.runner";
    private static final String PROCESS = "proc1";

    private SettingService settingService;
    private PermissionService permissionService;
    private ThinkProcessService thinkProcessService;
    private SettingSetTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        permissionService = mock(PermissionService.class);
        SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
        when(contextFactory.forToolSubject(TENANT, USER)).thenReturn(SecurityContext.user(USER, TENANT, List.of()));
        thinkProcessService = mock(ThinkProcessService.class);
        // The real policy with its shipped default patterns — the tool must
        // refuse what the operator denies, not what a test substitutes in.
        tool = new SettingSetTool(
                settingService,
                permissionService,
                contextFactory,
                new AgentSettingKeyPolicy("ai.provider.*,vault.*,store.*,kit.*"),
                thinkProcessService);
        ctx = new ToolInvocationContext(TENANT, PROJECT, "sess1", PROCESS, USER);
    }

    @Test
    void metadata_isDeferredWithSettingsLabel() {
        assertThat(tool.name()).isEqualTo("setting_set");
        assertThat(tool.primary()).isFalse();
        assertThat(tool.labels()).contains("settings");
    }

    @Test
    void invoke_ordinaryKey_enforcesAdminAndWritesString() {
        when(settingService.find(TENANT, "project", PROJECT, "research.default.web"))
                .thenReturn(Optional.empty());
        when(settingService.setAs(
                        eq(TENANT),
                        eq("project"),
                        eq(PROJECT),
                        eq("research.default.web"),
                        eq("serper-main"),
                        eq(SettingType.STRING),
                        isNull(),
                        eq(USER)))
                .thenReturn(setting("research.default.web", SettingType.STRING));

        Map<String, Object> out = tool.invoke(Map.of("key", "research.default.web", "value", "serper-main"), ctx);

        verify(permissionService)
                .enforce(
                        SecurityContext.user(USER, TENANT, List.of()),
                        new Resource.Setting(TENANT, "project", PROJECT, "research.default.web"),
                        Action.ADMIN);
        assertThat(out.get("projectId")).isEqualTo(PROJECT);
        assertThat(out.get("type")).isEqualTo("STRING");
        assertThat(out.get("created")).isEqualTo(true);
    }

    @Test
    void invoke_existingPlainSetting_keepsType() {
        when(settingService.find(TENANT, "project", PROJECT, "research.maxPerRun"))
                .thenReturn(Optional.of(setting("research.maxPerRun", SettingType.INT)));
        when(settingService.setAs(
                        eq(TENANT),
                        eq("project"),
                        eq(PROJECT),
                        eq("research.maxPerRun"),
                        eq("25"),
                        eq(SettingType.INT),
                        isNull(),
                        eq(USER)))
                .thenReturn(setting("research.maxPerRun", SettingType.INT));

        Map<String, Object> out = tool.invoke(Map.of("key", "research.maxPerRun", "value", "25"), ctx);

        assertThat(out.get("type")).isEqualTo("INT");
        assertThat(out.get("created")).isEqualTo(false);
    }

    @Test
    void invoke_existingPasswordSetting_refusedAndNeverWritten() {
        when(settingService.find(TENANT, "project", PROJECT, "ai.provider.coding-proxy.baseUrl"))
                .thenReturn(Optional.of(setting("ai.provider.coding-proxy.baseUrl", SettingType.PASSWORD)));

        assertThatThrownBy(() -> tool.invoke(
                        Map.of("key", "ai.provider.coding-proxy.baseUrl", "value", "https://x.example"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("PASSWORD");

        verify(settingService, never()).setAs(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void invoke_denyListedKey_withoutRecipeFlag_refused() {
        assertThatThrownBy(() -> tool.invoke(
                        Map.of("key", "ai.provider.coding-proxy.baseUrl", "value", "https://coding-proxy.example"),
                        ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("reserved for operator configuration");

        verify(settingService, never()).setAs(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void invoke_denyListedKey_withRecipeFlag_typeAndBaseUrlAllowed() {
        processWithFlag(true);

        when(settingService.find(TENANT, "project", PROJECT, "ai.provider.coding-proxy.baseUrl"))
                .thenReturn(Optional.empty());
        when(settingService.setAs(
                        eq(TENANT),
                        eq("project"),
                        eq(PROJECT),
                        eq("ai.provider.coding-proxy.baseUrl"),
                        eq("https://coding-proxy.example"),
                        eq(SettingType.STRING),
                        isNull(),
                        eq(USER)))
                .thenReturn(setting("ai.provider.coding-proxy.baseUrl", SettingType.STRING));

        Map<String, Object> out = tool.invoke(
                Map.of("key", "ai.provider.coding-proxy.baseUrl", "value", "https://coding-proxy.example"), ctx);

        assertThat(out.get("key")).isEqualTo("ai.provider.coding-proxy.baseUrl");
        assertThat(out.get("created")).isEqualTo(true);
    }

    @Test
    void invoke_carveOutNeverCoversTheCredential() {
        processWithFlag(true);

        assertThatThrownBy(
                        () -> tool.invoke(Map.of("key", "ai.provider.coding-proxy.apiKey", "value", "sk-secret"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("never credentials");

        // vault.* and friends are not grantable at all, flag or not.
        assertThatThrownBy(() -> tool.invoke(Map.of("key", "vault.clientSecret", "value", "x"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("reserved for operator configuration");

        verify(settingService, never()).setAs(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void invoke_tenantLayer_enforcesTenantScope() {
        when(settingService.find(TENANT, "project", "_tenant", "ai.alias.default.fast"))
                .thenReturn(Optional.empty());
        when(settingService.setAs(
                        eq(TENANT),
                        eq("project"),
                        eq("_tenant"),
                        eq("ai.alias.default.fast"),
                        eq("coding-proxy:gpt-5"),
                        eq(SettingType.STRING),
                        isNull(),
                        eq(USER)))
                .thenReturn(setting("ai.alias.default.fast", SettingType.STRING));

        tool.invoke(
                Map.of(
                        "key", "ai.alias.default.fast",
                        "value", "coding-proxy:gpt-5",
                        "projectId", "_tenant"),
                ctx);

        verify(permissionService)
                .enforce(
                        SecurityContext.user(USER, TENANT, List.of()),
                        new Resource.Setting(TENANT, "tenant", TENANT, "ai.alias.default.fast"),
                        Action.ADMIN);
    }

    @Test
    void invoke_deniedNonAdmin_propagatesAndNeverWrites() {
        doThrow(new PermissionDeniedException(
                        SecurityContext.user(USER, TENANT, List.of()),
                        new Resource.Setting(TENANT, "project", PROJECT, "research.default.web"),
                        Action.ADMIN))
                .when(permissionService)
                .enforce(any(), any(), any());

        assertThatThrownBy(() -> tool.invoke(Map.of("key", "research.default.web", "value", "serper-main"), ctx))
                .isInstanceOf(PermissionDeniedException.class);

        verify(settingService, never()).setAs(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── helpers ─────────────────────────────────────────────────────

    private void processWithFlag(boolean flag) {
        ThinkProcessDocument process = mock(ThinkProcessDocument.class);
        when(process.getEngineParams()).thenReturn(Map.of(SettingSetTool.PARAM_ALLOW_AI_PROVIDER, flag));
        when(thinkProcessService.findById(PROCESS)).thenReturn(Optional.of(process));
    }

    private static SettingDocument setting(String key, SettingType type) {
        return SettingDocument.builder()
                .tenantId(TENANT)
                .referenceType("project")
                .referenceId(PROJECT)
                .key(key)
                .type(type)
                .build();
    }
}
