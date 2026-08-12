package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.AgentSettingKeyPolicy;
import de.mhus.vance.shared.settings.SecretAccessDeniedException;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A kit installs a credential its own connector uses — an SMTP password, a REST
 * token. The connector resolves it through
 * {@code SecretResolver.resolveForConnector}, which reads both encrypted types,
 * so the credential stays {@link SettingType#PASSWORD}: agents and scripts must
 * not be able to read it back, and the fact that an agent triggered the install
 * does not change what the value is used for.
 *
 * <p>The origins differ in the guards, not in the resulting type: an
 * {@link SettingWriteOrigin#AGENT} write goes through {@code setAgentSecret}
 * (W1: no overwrite of an existing PASSWORD) and is checked against the
 * reserved-key deny-list (W3).
 */
class TemplateApplierSettingTypeTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";

    private final SettingService settingService = mock(SettingService.class);

    private TemplateApplier applierWith(String denyKeys) {
        return new TemplateApplier(
                mock(KitInstaller.class), settingService, mock(DocumentService.class),
                new AgentSettingKeyPolicy(denyKeys));
    }

    @Test
    void an_agent_write_goes_through_the_agent_secret_path_and_stays_password() {
        applierWith("").persistSetting(TENANT, PROJECT,
                settingTarget(TemplateInputType.PASSWORD, "smtp.password", "s3cr3t"),
                SettingWriteOrigin.AGENT);

        verify(settingService).setAgentSecret(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT),
                eq("smtp.password"), eq("s3cr3t"), eq(SettingType.PASSWORD));
    }

    @Test
    void a_human_write_stores_password_directly_without_the_agent_guards() {
        applierWith("").persistSetting(TENANT, PROJECT,
                settingTarget(TemplateInputType.PASSWORD, "smtp.password", "s3cr3t"),
                SettingWriteOrigin.USER);

        verify(settingService).setEncryptedSecret(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT),
                eq("smtp.password"), eq("s3cr3t"), eq(SettingType.PASSWORD));
        verify(settingService, never()).setAgentSecret(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT),
                eq("smtp.password"), eq("s3cr3t"), eq(SettingType.PASSWORD));
    }

    @Test
    void an_agent_write_to_a_reserved_key_is_refused_before_it_reaches_the_service() {
        assertThatThrownBy(() -> applierWith("ai.provider.*,vault.*")
                .persistSetting(TENANT, PROJECT,
                        settingTarget(TemplateInputType.PASSWORD,
                                "ai.provider.default.apiKey", "sk-agent"),
                        SettingWriteOrigin.AGENT))
                .isInstanceOf(SecretAccessDeniedException.class)
                .hasMessageContaining("reserved for operator configuration");
    }

    @Test
    void the_same_reserved_key_is_writable_on_the_human_path() {
        // W3 constrains the agent, not the operator applying a template by hand.
        applierWith("ai.provider.*,vault.*").persistSetting(TENANT, PROJECT,
                settingTarget(TemplateInputType.PASSWORD, "ai.provider.default.apiKey", "sk-human"),
                SettingWriteOrigin.USER);

        verify(settingService).setEncryptedSecret(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT),
                eq("ai.provider.default.apiKey"), eq("sk-human"), eq(SettingType.PASSWORD));
    }

    @Test
    void non_password_input_still_lands_as_a_plain_string_setting() {
        applierWith("").persistSetting(TENANT, PROJECT,
                settingTarget(TemplateInputType.STRING, "smtp.host", "smtp.example.com"),
                SettingWriteOrigin.AGENT);

        verify(settingService).set(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT),
                eq("smtp.host"), eq("smtp.example.com"), eq(SettingType.STRING), eq(null));
    }

    @Test
    void a_target_outside_the_apply_project_is_still_rejected() {
        // The confinement guard predates all of this and must survive it — a
        // template must not reach _tenant, where the compiled-read secrets live.
        TemplateApplier.SettingTarget st = new TemplateApplier.SettingTarget(
                new TemplateInput(
                        "apiKey", TemplateInputType.PASSWORD, "API key", null, true,
                        null, List.of(),
                        new TemplateInputTarget(TemplateInputTarget.Kind.SETTING,
                                TemplateInputTarget.Scope.TENANT, null,
                                "ai.provider.default.apiKey")),
                "stolen");

        assertThatThrownBy(() -> applierWith("")
                .persistSetting(TENANT, PROJECT, st, SettingWriteOrigin.AGENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the apply-project");
    }

    private static TemplateApplier.SettingTarget settingTarget(
            TemplateInputType type, String key, String value) {
        return new TemplateApplier.SettingTarget(
                new TemplateInput(
                        "field", type, "Field", null, true, null, List.of(),
                        new TemplateInputTarget(TemplateInputTarget.Kind.SETTING,
                                TemplateInputTarget.Scope.PROJECT, null, key)),
                value);
    }
}
