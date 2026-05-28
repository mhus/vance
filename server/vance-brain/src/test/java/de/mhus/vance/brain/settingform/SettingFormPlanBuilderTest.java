package de.mhus.vance.brain.settingform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.BindsToDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettingFormPlanBuilderTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String USER = "alice";

    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final SettingService settingService = mock(SettingService.class);
    private final SettingFormPlanBuilder planBuilder = new SettingFormPlanBuilder(renderer, settingService);

    @Test
    void direct_mapped_field_with_value_emits_WRITE() {
        ResolvedSettingForm form = formWith(
                List.of(stringField("provider", "ai.default.provider", null, null)),
                List.of());

        List<PlannedSettingAction> plan = planBuilder.buildApplyPlan(
                form, Map.of("provider", "anthropic"), TENANT, PROJECT, USER, "en");

        assertThat(plan).hasSize(1);
        PlannedSettingAction a = plan.get(0);
        assertThat(a.action()).isEqualTo(PlannedSettingAction.Action.WRITE);
        assertThat(a.key()).isEqualTo("ai.default.provider");
        assertThat(a.value()).isEqualTo("anthropic");
        assertThat(a.settingType()).isEqualTo(SettingType.STRING);
    }

    @Test
    void password_field_with_empty_value_is_SKIP() {
        ResolvedSettingForm form = formWith(
                List.of(passwordField("anthKey", "ai.providers.anthropic.api_key")),
                List.of());

        List<PlannedSettingAction> plan = planBuilder.buildApplyPlan(
                form, Map.of("anthKey", ""), TENANT, PROJECT, USER, "en");

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).action()).isEqualTo(PlannedSettingAction.Action.SKIP);
        assertThat(plan.get(0).masked()).isTrue();
    }

    @Test
    void writeIf_false_emits_DELETE_for_direct_mapped_field() {
        ResolvedSettingForm form = formWith(
                List.of(
                        stringField("provider", "ai.default.provider", null, null),
                        passwordField("anthKey", "ai.providers.anthropic.api_key",
                                null, "provider == 'anthropic'")),
                List.of());

        List<PlannedSettingAction> plan = planBuilder.buildApplyPlan(
                form, Map.of("provider", "openai", "anthKey", "secret"),
                TENANT, PROJECT, USER, "en");

        assertThat(plan).extracting(PlannedSettingAction::action, PlannedSettingAction::key)
                .containsExactly(
                        tuple(PlannedSettingAction.Action.WRITE, "ai.default.provider"),
                        tuple(PlannedSettingAction.Action.DELETE, "ai.providers.anthropic.api_key"));
    }

    @Test
    void showIf_false_skips_field_binding_entirely() {
        ResolvedSettingForm form = formWith(
                List.of(
                        stringField("provider", "ai.default.provider", null, null),
                        passwordField("anthKey", "ai.providers.anthropic.api_key",
                                "provider == 'anthropic'", null)),
                List.of());

        List<PlannedSettingAction> plan = planBuilder.buildApplyPlan(
                form, Map.of("provider", "openai", "anthKey", "should-be-ignored"),
                TENANT, PROJECT, USER, "en");

        assertThat(plan).hasSize(1);
        assertThat(plan.get(0).key()).isEqualTo("ai.default.provider");
    }

    @Test
    void computed_setting_renders_pebble_template() {
        ResolvedComputedSetting cs = new ResolvedComputedSetting(
                "quota.daily_tokens", null, SettingType.LONG,
                "{% if budget == 'small' %}100000{% else %}500000{% endif %}",
                null, null);
        ResolvedSettingForm form = formWith(
                List.of(stringField("budget", null, null, null)),
                List.of(cs));

        List<PlannedSettingAction> planSmall = planBuilder.buildApplyPlan(
                form, Map.of("budget", "small"), TENANT, PROJECT, USER, "en");
        List<PlannedSettingAction> planLarge = planBuilder.buildApplyPlan(
                form, Map.of("budget", "large"), TENANT, PROJECT, USER, "en");

        assertThat(planSmall).filteredOn(a -> a.key().equals("quota.daily_tokens"))
                .singleElement()
                .extracting(PlannedSettingAction::value).isEqualTo("100000");
        assertThat(planLarge).filteredOn(a -> a.key().equals("quota.daily_tokens"))
                .singleElement()
                .extracting(PlannedSettingAction::value).isEqualTo("500000");
    }

    @Test
    void computed_setting_writeIf_false_emits_DELETE() {
        ResolvedComputedSetting cs = new ResolvedComputedSetting(
                "tracing.llm.sample_rate", null, SettingType.DOUBLE,
                "1.0", "tracing", null);
        ResolvedSettingForm form = formWith(
                List.of(booleanField("tracing")),
                List.of(cs));

        List<PlannedSettingAction> planOff = planBuilder.buildApplyPlan(
                form, Map.of("tracing", "false"), TENANT, PROJECT, USER, "en");

        assertThat(planOff).filteredOn(a -> a.key().equals("tracing.llm.sample_rate"))
                .singleElement()
                .extracting(PlannedSettingAction::action)
                .isEqualTo(PlannedSettingAction.Action.DELETE);
    }

    @Test
    void mutually_exclusive_writeIf_yields_one_WRITE_one_DELETE_for_same_key() {
        // Custom-vs-preset pattern: two entries on the same key, gated mutually-exclusive.
        FormFieldDto custom = FormFieldDto.builder()
                .name("customValue")
                .type("integer")
                .label(Map.of("en", "Custom"))
                .writeIf("mode == 'custom'")
                .bindsTo(BindsToDto.builder().key("x.value").build())
                .build();
        ResolvedComputedSetting preset = new ResolvedComputedSetting(
                "x.value", null, SettingType.INT, "42", "mode == 'preset'", null);
        ResolvedSettingForm form = formWith(
                List.of(stringField("mode", null, null, null), custom),
                List.of(preset));

        List<PlannedSettingAction> planCustom = planBuilder.buildApplyPlan(
                form, Map.of("mode", "custom", "customValue", "99"),
                TENANT, PROJECT, USER, "en");

        // After merge, the single output for x.value is a WRITE with value 99.
        assertThat(planCustom).filteredOn(a -> a.key().equals("x.value"))
                .singleElement()
                .satisfies(a -> {
                    assertThat(a.action()).isEqualTo(PlannedSettingAction.Action.WRITE);
                    assertThat(a.value()).isEqualTo("99");
                });
    }

    @Test
    void two_concurrent_WRITES_on_same_key_throw() {
        // Two fields both writing the same key with no mutually-exclusive guard.
        ResolvedSettingForm form = formWith(
                List.of(
                        stringField("a", "k", null, null),
                        stringField("b", "k", null, null)),
                List.of());
        // The loader would have rejected this; we bypass it here to exercise
        // the runtime safety net in the planner.

        assertThatThrownBy(() -> planBuilder.buildApplyPlan(
                form, Map.of("a", "1", "b", "2"), TENANT, PROJECT, USER, "en"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate WRITE");
    }

    @Test
    void scope_resolution_maps_wire_to_storage() {
        SettingFormPlanBuilder.ResolvedScope tenant =
                planBuilder.resolveScope(SettingService.SCOPE_TENANT, PROJECT, USER);
        assertThat(tenant.referenceType()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(tenant.referenceId()).isEqualTo(HomeBootstrapService.TENANT_PROJECT_NAME);

        SettingFormPlanBuilder.ResolvedScope user =
                planBuilder.resolveScope(SettingService.SCOPE_USER, PROJECT, USER);
        assertThat(user.referenceType()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(user.referenceId()).isEqualTo("_user_" + USER);

        SettingFormPlanBuilder.ResolvedScope project =
                planBuilder.resolveScope(SettingService.SCOPE_PROJECT, PROJECT, USER);
        assertThat(project.referenceType()).isEqualTo(SettingService.SCOPE_PROJECT);
        assertThat(project.referenceId()).isEqualTo(PROJECT);
    }

    @Test
    void scope_project_without_projectId_rejects() {
        assertThatThrownBy(() -> planBuilder.resolveScope(
                SettingService.SCOPE_PROJECT, null, USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    void scope_user_without_userId_rejects() {
        assertThatThrownBy(() -> planBuilder.resolveScope(
                SettingService.SCOPE_USER, PROJECT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user");
    }

    @Test
    void reset_plan_emits_DELETE_for_every_referenced_key() {
        ResolvedSettingForm form = formWith(
                List.of(
                        stringField("provider", "ai.default.provider", null, null),
                        passwordField("key", "ai.providers.anthropic.api_key")),
                List.of(new ResolvedComputedSetting(
                        "tracing.llm.enabled", null, SettingType.BOOLEAN,
                        "true", null, null)));

        List<PlannedSettingAction> reset = planBuilder.buildResetPlan(form, PROJECT, USER);

        assertThat(reset).hasSize(3);
        assertThat(reset).allSatisfy(
                a -> assertThat(a.action()).isEqualTo(PlannedSettingAction.Action.DELETE));
        assertThat(reset).extracting(PlannedSettingAction::key)
                .containsExactlyInAnyOrder(
                        "ai.default.provider",
                        "ai.providers.anthropic.api_key",
                        "tracing.llm.enabled");
    }

    @Test
    void current_variable_carries_live_cascade_values_into_pebble_context() {
        when(settingService.getStringValueCascade(eq(TENANT), eq(PROJECT), any(), eq("ai.default.model")))
                .thenReturn("claude-sonnet-4-6");
        ResolvedComputedSetting cs = new ResolvedComputedSetting(
                "ai.default.model.echo", null, SettingType.STRING,
                "{{ current['ai.default.model'] | default('unset') }}",
                null, null);
        ResolvedSettingForm form = formWith(
                List.of(stringField("dummy", "ai.default.model", null, null)),
                List.of(cs));

        List<PlannedSettingAction> plan = planBuilder.buildApplyPlan(
                form, Map.of("dummy", "claude-opus-4-7"),
                TENANT, PROJECT, USER, "en");

        assertThat(plan).filteredOn(a -> a.key().equals("ai.default.model.echo"))
                .singleElement()
                .extracting(PlannedSettingAction::value).isEqualTo("claude-sonnet-4-6");
    }

    // ──────────────────── helpers ────────────────────

    private static ResolvedSettingForm formWith(
            List<FormFieldDto> fields,
            List<ResolvedComputedSetting> computed) {
        return new ResolvedSettingForm(
                "test", Map.of("en", "Test"), Map.of("en", "Test"),
                null, null, SettingService.SCOPE_PROJECT,
                fields, computed, true, List.of("*"),
                SettingFormSource.RESOURCE);
    }

    private static FormFieldDto stringField(
            String name, String key, String showIf, String writeIf) {
        FormFieldDto.FormFieldDtoBuilder b = FormFieldDto.builder()
                .name(name)
                .type("string")
                .label(Map.of("en", name))
                .showIf(showIf)
                .writeIf(writeIf);
        if (key != null) {
            b.bindsTo(BindsToDto.builder().key(key).build());
        }
        return b.build();
    }

    private static FormFieldDto passwordField(String name, String key) {
        return passwordField(name, key, null, null);
    }

    private static FormFieldDto passwordField(
            String name, String key, String showIf, String writeIf) {
        return FormFieldDto.builder()
                .name(name)
                .type("password")
                .label(Map.of("en", name))
                .showIf(showIf)
                .writeIf(writeIf)
                .bindsTo(BindsToDto.builder().key(key).build())
                .build();
    }

    private static FormFieldDto booleanField(String name) {
        return FormFieldDto.builder()
                .name(name)
                .type("boolean")
                .label(Map.of("en", name))
                .build();
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
