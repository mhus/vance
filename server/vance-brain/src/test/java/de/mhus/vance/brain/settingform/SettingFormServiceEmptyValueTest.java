package de.mhus.vance.brain.settingform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.BindsToDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.form.FormValidator;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Empty-value semantics of the setting-form apply path (spec §6.4).
 *
 * <p>An empty submission is ambiguous on its own, so the planner resolves
 * it against the cascade layer that holds the live value. This test pins
 * the layer <em>detection</em> — i.e. that the service reads the right
 * scopes and hands the planner the right {@link FieldLiveState} — and the
 * resulting write/delete against {@link SettingService}.
 * {@code SettingFormPlanBuilderTest} covers the branch table itself.
 *
 * <p>Concrete case behind it: the tenant routes OpenAI through a gateway
 * ({@code https://api.cortecs.ai/v1}) and one project must run against
 * real OpenAI. Emptying the field in the project has to stop the cascade
 * there instead of silently keeping the inherited gateway.
 */
class SettingFormServiceEmptyValueTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "openai-direct";
    private static final String TENANT_PROJECT = "_tenant";
    private static final String KEY = "ai.provider.openai.baseUrl";
    private static final String GATEWAY = "https://api.cortecs.ai/v1";

    private final SettingService settingService = mock(SettingService.class);
    private final ModelCatalog modelCatalog = mock(ModelCatalog.class);
    private final FormValidator formValidator = new FormValidator();
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final SettingFormPlanBuilder planBuilder =
            new SettingFormPlanBuilder(renderer, settingService);
    private final SettingFormService service = new SettingFormService(
            settingService, formValidator, planBuilder, modelCatalog);

    @Test
    void emptying_an_inherited_value_writes_an_empty_string_into_the_edited_project() {
        liveValue(PROJECT, null);
        liveValue(TENANT_PROJECT, GATEWAY);

        List<PlannedSettingAction> plan = service.apply(
                form(), Map.of("baseUrl", ""), TENANT, PROJECT, "alice", "en");

        assertThat(plan).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo(PlannedSettingAction.Action.WRITE);
            assertThat(a.referenceId()).isEqualTo(PROJECT);
            assertThat(a.value()).isEmpty();
        });
        // The persisted "" is what stops getStringValueCascade at the project
        // layer — a null value would keep falling through to the tenant.
        // setAs, not set: the acting user travels into the setting.change row.
        verify(settingService).setAs(
                TENANT, SettingService.SCOPE_PROJECT, PROJECT, KEY, "", SettingType.STRING,
                null, "alice");
    }

    @Test
    void emptying_a_value_the_project_owns_deletes_it_and_restores_inheritance() {
        liveValue(PROJECT, "https://my-vllm.internal/v1");
        liveValue(TENANT_PROJECT, GATEWAY);

        List<PlannedSettingAction> plan = service.apply(
                form(), Map.of("baseUrl", ""), TENANT, PROJECT, "alice", "en");

        assertThat(plan).singleElement()
                .extracting(PlannedSettingAction::action)
                .isEqualTo(PlannedSettingAction.Action.DELETE);
        verify(settingService).delete(TENANT, SettingService.SCOPE_PROJECT, PROJECT, KEY);
    }

    @Test
    void emptying_a_field_that_is_unset_everywhere_writes_nothing() {
        liveValue(PROJECT, null);
        liveValue(TENANT_PROJECT, null);

        List<PlannedSettingAction> plan = service.apply(
                form(), Map.of("baseUrl", ""), TENANT, PROJECT, "alice", "en");

        assertThat(plan).singleElement()
                .extracting(PlannedSettingAction::action)
                .isEqualTo(PlannedSettingAction.Action.SKIP);
        verify(settingService, never()).set(
                any(), any(), any(), any(), any(), any(), any());
        verify(settingService, never()).delete(any(), any(), any(), any());
    }

    @Test
    void resubmitting_an_already_explicitly_empty_field_is_a_no_op() {
        // The project already holds "" — the pre-filled form comes back empty,
        // which the unchanged-check must catch before the empty-value rules.
        liveValue(PROJECT, "");
        liveValue(TENANT_PROJECT, GATEWAY);

        List<PlannedSettingAction> plan = service.apply(
                form(), Map.of("baseUrl", ""), TENANT, PROJECT, "alice", "en");

        assertThat(plan).singleElement()
                .extracting(PlannedSettingAction::action)
                .isEqualTo(PlannedSettingAction.Action.SKIP);
        verify(settingService, never()).set(
                any(), any(), any(), any(), any(), any(), any());
        verify(settingService, never()).delete(any(), any(), any(), any());
    }

    @Test
    void a_field_that_was_not_submitted_at_all_is_left_alone() {
        // Partial submit: the key is absent from `values`. "Not sent" must not
        // be read as "cleared" — otherwise a caller that only submits the one
        // field it wants to change would blank every other inherited value.
        liveValue(PROJECT, null);
        liveValue(TENANT_PROJECT, GATEWAY);

        List<PlannedSettingAction> plan = service.apply(
                form(), Map.of(), TENANT, PROJECT, "alice", "en");

        assertThat(plan).singleElement()
                .extracting(PlannedSettingAction::action)
                .isEqualTo(PlannedSettingAction.Action.SKIP);
        verify(settingService, never()).set(
                any(), any(), any(), any(), any(), any(), any());
        verify(settingService, never()).delete(any(), any(), any(), any());
    }

    @Test
    void a_real_value_still_wins_over_the_inherited_one() {
        liveValue(PROJECT, null);
        liveValue(TENANT_PROJECT, GATEWAY);

        List<PlannedSettingAction> plan = service.apply(
                form(), Map.of("baseUrl", "https://openrouter.ai/api/v1"),
                TENANT, PROJECT, "alice", "en");

        assertThat(plan).singleElement().satisfies(a -> {
            assertThat(a.action()).isEqualTo(PlannedSettingAction.Action.WRITE);
            assertThat(a.value()).isEqualTo("https://openrouter.ai/api/v1");
        });
    }

    // ──────────────────── helpers ────────────────────

    private void liveValue(String referenceId, @Nullable String value) {
        when(settingService.getStringValue(
                TENANT, SettingService.SCOPE_PROJECT, referenceId, KEY)).thenReturn(value);
    }

    private static ResolvedSettingForm form() {
        FormFieldDto field = FormFieldDto.builder()
                .name("baseUrl")
                .type("string")
                .label(Map.of("en", "OpenAI Base-URL"))
                .bindsTo(BindsToDto.builder().key(KEY).build())
                .build();
        return new ResolvedSettingForm(
                "llm-setup", Map.of("en", "LLM"), Map.of("en", "LLM"),
                null, null, SettingService.SCOPE_PROJECT,
                List.of(field), List.of(), true, List.of("*"),
                SettingFormSource.RESOURCE);
    }
}
