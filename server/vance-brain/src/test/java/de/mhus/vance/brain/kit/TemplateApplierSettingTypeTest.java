package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A kit template stores a secret so the kit's own documents can reference it via
 * {@code {{secret:…}}} at runtime — that is the documented purpose of
 * {@link TemplateInputTarget.Kind#SETTING}. Since PASSWORD-typed settings are not
 * reference-readable, such a write must land as
 * {@link SettingType#HIDDEN}; writing PASSWORD would install a credential the
 * installed tool cannot use.
 */
class TemplateApplierSettingTypeTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "instant-hole";

    private final SettingService settingService = mock(SettingService.class);
    private final TemplateApplier applier = new TemplateApplier(
            mock(KitInstaller.class), settingService, mock(DocumentService.class));

    @Test
    void password_input_is_stored_as_hidden_so_the_kit_document_can_reference_it() {
        applier.persistSetting(TENANT, PROJECT, settingTarget(
                TemplateInputType.PASSWORD, "smtp.password", "s3cr3t"));

        verify(settingService).setEncryptedSecret(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT),
                eq("smtp.password"), eq("s3cr3t"), eq(SettingType.HIDDEN));
        verify(settingService, never()).setEncryptedPassword(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT),
                eq("smtp.password"), eq("s3cr3t"));
    }

    @Test
    void non_password_input_still_lands_as_a_plain_string_setting() {
        applier.persistSetting(TENANT, PROJECT, settingTarget(
                TemplateInputType.STRING, "smtp.host", "smtp.example.com"));

        verify(settingService).set(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT),
                eq("smtp.host"), eq("smtp.example.com"), eq(SettingType.STRING), eq(null));
    }

    @Test
    void a_target_outside_the_apply_project_is_still_rejected() {
        // The confinement guard predates this change and must survive it — a
        // template must not reach _tenant, where the compiled-read secrets live.
        TemplateApplier.SettingTarget st = new TemplateApplier.SettingTarget(
                new TemplateInput(
                        "apiKey", TemplateInputType.PASSWORD, "API key", null, true,
                        null, List.of(),
                        new TemplateInputTarget(TemplateInputTarget.Kind.SETTING,
                                TemplateInputTarget.Scope.TENANT, null,
                                "ai.provider.default.apiKey")),
                "stolen");

        assertThatThrownBy(() -> applier.persistSetting(TENANT, PROJECT, st))
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
