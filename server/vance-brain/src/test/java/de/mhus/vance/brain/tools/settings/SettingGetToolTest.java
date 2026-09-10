package de.mhus.vance.brain.tools.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The read twin has one rule that must not drift: PASSWORD values never
 * leave the server — the mask answers "is it set" without answering
 * "what is it". HIDDEN follows the opposite contract: the decrypted
 * plaintext IS returned, because that is what the type exists for — a
 * secret scripts and agents resolve themselves. The tests pin both
 * sides of that threshold plus the cascade walk in consumption order,
 * with the source layer reported so inheritance is visible.
 */
class SettingGetToolTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "model-sipgate-coding";
    private static final String USER = "road.runner";
    private static final String PROCESS = "proc1";

    private SettingService settingService;
    private PermissionService permissionService;
    private SettingGetTool tool;
    private ToolInvocationContext ctx;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        permissionService = mock(PermissionService.class);
        SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
        when(contextFactory.forToolSubject(TENANT, USER)).thenReturn(SecurityContext.user(USER, TENANT, List.of()));
        tool = new SettingGetTool(settingService, permissionService, contextFactory);
        ctx = new ToolInvocationContext(TENANT, PROJECT, "sess1", PROCESS, USER);
    }

    @Test
    void metadata_isDeferredWithSettingsLabel() {
        assertThat(tool.name()).isEqualTo("setting_get");
        assertThat(tool.primary()).isFalse();
        assertThat(tool.labels()).contains("settings");
    }

    @Test
    void invoke_projectLayer_enforcesReadAndReturnsValueWithScope() {
        when(settingService.find(TENANT, "project", PROJECT, "ai.provider.coding-proxy.baseUrl"))
                .thenReturn(Optional.of(
                        doc("ai.provider.coding-proxy.baseUrl", SettingType.STRING, "https://coding-proxy.example")));

        Map<String, Object> out = tool.invoke(Map.of("key", "ai.provider.coding-proxy.baseUrl"), ctx);

        verify(permissionService)
                .enforce(
                        SecurityContext.user(USER, TENANT, List.of()),
                        new Resource.Setting(TENANT, "project", PROJECT, "ai.provider.coding-proxy.baseUrl"),
                        Action.READ);
        assertThat(out.get("found")).isEqualTo(true);
        assertThat(out.get("scope")).isEqualTo("project:" + PROJECT);
        assertThat(out.get("value")).isEqualTo("https://coding-proxy.example");
        assertThat(out.get("masked")).isEqualTo(false);
        // Plain reads carry neither the confidential marker nor a note.
        assertThat(out).doesNotContainKey("confidential");
        assertThat(out).doesNotContainKey("note");
    }

    @Test
    void invoke_fallsThroughToTenantLayer_andNamesTheScope() {
        when(settingService.find(TENANT, "think-process", PROCESS, "ai.alias.default.fast"))
                .thenReturn(Optional.empty());
        when(settingService.find(TENANT, "project", PROJECT, "ai.alias.default.fast"))
                .thenReturn(Optional.empty());
        when(settingService.find(TENANT, "project", "_tenant", "ai.alias.default.fast"))
                .thenReturn(Optional.of(doc("ai.alias.default.fast", SettingType.STRING, "cortecs:deepseek-v4-pro")));

        Map<String, Object> out = tool.invoke(Map.of("key", "ai.alias.default.fast"), ctx);

        assertThat(out.get("scope")).isEqualTo("_tenant");
        assertThat(out.get("value")).isEqualTo("cortecs:deepseek-v4-pro");
    }

    @Test
    void invoke_encryptedSetting_masksTheValue() {
        when(settingService.find(TENANT, "project", PROJECT, "ai.provider.coding-proxy.apiKey"))
                .thenReturn(Optional.of(
                        doc("ai.provider.coding-proxy.apiKey", SettingType.PASSWORD, "sk-plaintext-secret")));

        Map<String, Object> out = tool.invoke(Map.of("key", "ai.provider.coding-proxy.apiKey"), ctx);

        assertThat(out.get("found")).isEqualTo(true);
        assertThat(out.get("value")).isEqualTo("[set]");
        assertThat(out.get("masked")).isEqualTo(true);
        assertThat(String.valueOf(out.get("value"))).doesNotContain("sk-plaintext-secret");
    }

    @Test
    void invoke_hiddenSetting_returnsTheDecryptedValue() {
        // HIDDEN's contract: a secret scripts and agents resolve themselves —
        // the decrypted plaintext is returned, never the stored ciphertext.
        when(settingService.find(TENANT, "project", PROJECT, "ai.provider.coding-proxy.apiKey"))
                .thenReturn(Optional.of(doc("ai.provider.coding-proxy.apiKey", SettingType.HIDDEN, "<ciphertext>")));
        when(settingService.getDecryptedPassword(TENANT, "project", PROJECT, "ai.provider.coding-proxy.apiKey"))
                .thenReturn("sk-real-key");

        Map<String, Object> out = tool.invoke(Map.of("key", "ai.provider.coding-proxy.apiKey"), ctx);

        assertThat(out.get("found")).isEqualTo(true);
        assertThat(out.get("value")).isEqualTo("sk-real-key");
        assertThat(out.get("masked")).isEqualTo(false);
        assertThat(String.valueOf(out.get("value"))).doesNotContain("ciphertext");
        // The decrypted secret travels with a loud handling instruction —
        // the value is for the task, not for the chat.
        assertThat(out.get("confidential")).isEqualTo(true);
        assertThat(String.valueOf(out.get("note"))).contains("CONFIDENTIAL").contains("Do not show");
    }

    @Test
    void invoke_unsetKey_reportsNotFound() {
        when(settingService.find(any(), any(), any(), any())).thenReturn(Optional.empty());

        Map<String, Object> out = tool.invoke(Map.of("key", "research.default.web"), ctx);

        assertThat(out.get("found")).isEqualTo(false);
        assertThat(String.valueOf(out.get("note"))).contains("cascade");
    }

    @Test
    void invoke_tenantRead_requiresTenantAdmin() {
        when(settingService.find(TENANT, "project", "_tenant", "ai.alias.default.fast"))
                .thenReturn(Optional.of(doc("ai.alias.default.fast", SettingType.STRING, "x")));

        tool.invoke(Map.of("key", "ai.alias.default.fast", "projectId", "_tenant"), ctx);

        verify(permissionService)
                .enforce(
                        SecurityContext.user(USER, TENANT, List.of()),
                        new Resource.Setting(TENANT, "tenant", TENANT, "ai.alias.default.fast"),
                        Action.ADMIN);
    }

    @Test
    void invoke_denied_propagatesWithoutAnyLookup() {
        doThrow(new PermissionDeniedException(
                        SecurityContext.user(USER, TENANT, List.of()),
                        new Resource.Setting(TENANT, "project", PROJECT, "research.default.web"),
                        Action.READ))
                .when(permissionService)
                .enforce(any(), any(), any());

        assertThatThrownBy(() -> tool.invoke(Map.of("key", "research.default.web"), ctx))
                .isInstanceOf(PermissionDeniedException.class);
    }

    private static SettingDocument doc(String key, SettingType type, String value) {
        return SettingDocument.builder()
                .tenantId(TENANT)
                .referenceType("project")
                .referenceId(PROJECT)
                .key(key)
                .type(type)
                .value(value)
                .build();
    }
}
