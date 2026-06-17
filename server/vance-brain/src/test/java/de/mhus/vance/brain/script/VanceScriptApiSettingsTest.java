package de.mhus.vance.brain.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.tools.ContextToolsApi;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code vance.settings.*} surface of {@link VanceScriptApi}
 * against a mocked {@link SettingService}: cascade scope cascading,
 * typed getters with defaults, and the null-service degrade path.
 */
class VanceScriptApiSettingsTest {

    private SettingService settingService;
    private VanceScriptApi api;

    @BeforeEach
    void setUp() {
        settingService = mock(SettingService.class);
        api = new VanceScriptApi(
                contextTools("acme", "proj", "sess", "proc", "alice"),
                null, Set.of(), null, null, null, null, null, settingService);
    }

    @Test
    void get_passesTenantProjectProcessAndKey_throughToService() {
        when(settingService.getStringValueCascade(
                eq("acme"), eq("proj"), eq("proc"), eq("mail.pack")))
                .thenReturn("zoho_imap");

        String result = api.settings.get("mail.pack");

        assertThat(result).isEqualTo("zoho_imap");
    }

    @Test
    void get_missingValue_returnsNull() {
        when(settingService.getStringValueCascade(
                eq("acme"), eq("proj"), eq("proc"), eq("mail.pack")))
                .thenReturn(null);

        assertThat(api.settings.get("mail.pack")).isNull();
    }

    @Test
    void get_withDefault_returnsDefaultOnMissing() {
        when(settingService.getStringValueCascade(
                eq("acme"), eq("proj"), eq("proc"), eq("mail.pack")))
                .thenReturn(null);

        assertThat(api.settings.get("mail.pack", "fallback")).isEqualTo("fallback");
    }

    @Test
    void get_withDefault_returnsDefaultOnBlank() {
        when(settingService.getStringValueCascade(
                eq("acme"), eq("proj"), eq("proc"), eq("mail.pack")))
                .thenReturn("   ");

        assertThat(api.settings.get("mail.pack", "fallback")).isEqualTo("fallback");
    }

    @Test
    void getInt_parsesValue() {
        when(settingService.getStringValueCascade(
                eq("acme"), eq("proj"), eq("proc"), eq("mail.maxPerRun")))
                .thenReturn("12");

        assertThat(api.settings.getInt("mail.maxPerRun", 5)).isEqualTo(12);
    }

    @Test
    void getInt_unparseable_returnsDefault() {
        when(settingService.getStringValueCascade(
                eq("acme"), eq("proj"), eq("proc"), eq("mail.maxPerRun")))
                .thenReturn("nicht-eine-zahl");

        assertThat(api.settings.getInt("mail.maxPerRun", 5)).isEqualTo(5);
    }

    @Test
    void getLong_parses() {
        when(settingService.getStringValueCascade(
                eq("acme"), eq("proj"), eq("proc"), eq("k")))
                .thenReturn("9999999999");

        assertThat(api.settings.getLong("k", 0L)).isEqualTo(9999999999L);
    }

    @Test
    void getDouble_parses() {
        when(settingService.getStringValueCascade(
                eq("acme"), eq("proj"), eq("proc"), eq("k")))
                .thenReturn("3.14");

        assertThat(api.settings.getDouble("k", 0.0)).isEqualTo(3.14);
    }

    @Test
    void getBoolean_delegatesToCascadeVariant() {
        when(settingService.getBooleanValueCascade(
                eq("acme"), eq("proj"), eq("proc"), eq("mail.dryRun"), eq(false)))
                .thenReturn(true);

        assertThat(api.settings.getBoolean("mail.dryRun", false)).isTrue();
    }

    @Test
    void emptyKey_rejected() {
        assertThatThrownBy(() -> api.settings.get(""))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("key");
    }

    @Test
    void noTenantScope_rejected() {
        VanceScriptApi noTenant = new VanceScriptApi(
                contextTools(null, null, null, null, null),
                null, Set.of(), null, null, null, null, null, settingService);

        assertThatThrownBy(() -> noTenant.settings.get("anything"))
                .isInstanceOf(VanceScriptApi.ScriptHostException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void apiWithoutSettingService_hasNullSettingsField() {
        VanceScriptApi noSettings = new VanceScriptApi(
                contextTools("acme", "proj", "sess", "proc", "alice"),
                null, Set.of(), null, null, null, null, null, null);

        assertThat(noSettings.settings).isNull();
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static ContextToolsApi contextTools(
            String tenant, String project, String session, String process, String user) {
        ContextToolsApi tools = mock(ContextToolsApi.class);
        when(tools.scope()).thenReturn(
                new ToolInvocationContext(tenant, project, session, process, user));
        return tools;
    }
}
