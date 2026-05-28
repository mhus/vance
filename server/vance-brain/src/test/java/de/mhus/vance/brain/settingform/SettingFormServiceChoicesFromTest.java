package de.mhus.vance.brain.settingform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.form.BindsToDto;
import de.mhus.vance.api.form.FormFieldDto;
import de.mhus.vance.brain.ai.ModelCapability;
import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelInfo;
import de.mhus.vance.brain.ai.ModelSize;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import de.mhus.vance.shared.form.FormValidationException;
import de.mhus.vance.shared.form.FormValidator;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the choicesFrom path through apply / validate.
 *
 * <p>The original bug: {@code FormValidator} ran against the
 * {@code ResolvedSettingForm.fields()} raw — which carries an empty
 * {@code choices} list for fields declared with
 * {@code choicesFrom: ai-models}. Result: every submitted value
 * tripped {@code invalid_choice}, the GET endpoint rendered the
 * dropdowns correctly, but apply/validate rejected every selection.
 *
 * <p>Fix: {@code resolveDynamicChoices} is now run inside
 * {@code apply} / {@code validate} too, not only in
 * {@code withLiveCascadeValues}. This test pins both flows.
 */
class SettingFormServiceChoicesFromTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "lissabon-reise";

    private final SettingService settingService = mock(SettingService.class);
    private final ModelCatalog modelCatalog = mock(ModelCatalog.class);
    private final FormValidator formValidator = new FormValidator();
    private final PromptTemplateRenderer renderer = new PromptTemplateRenderer();
    private final SettingFormPlanBuilder planBuilder = new SettingFormPlanBuilder(renderer, settingService);
    private final SettingFormService service = new SettingFormService(
            settingService, formValidator, planBuilder, modelCatalog);

    @Test
    void validate_accepts_a_value_present_in_the_ai_models_catalog() {
        when(modelCatalog.listAll(any(), any())).thenReturn(List.of(
                model("gemini", "gemini-2.5-pro", ModelSize.LARGE),
                model("gemini", "gemini-2.5-flash", ModelSize.SMALL),
                model("anthropic", "claude-sonnet-4-6", ModelSize.LARGE)));

        ResolvedSettingForm form = formWithAliasField();
        Map<String, Object> values = Map.of("aliasAnalyze", "gemini:gemini-2.5-pro");

        assertThatCode(() ->
                service.validate(form, values, TENANT, PROJECT, "alice", "en"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_still_rejects_a_value_outside_the_catalog() {
        when(modelCatalog.listAll(any(), any())).thenReturn(List.of(
                model("gemini", "gemini-2.5-pro", ModelSize.LARGE)));

        ResolvedSettingForm form = formWithAliasField();
        Map<String, Object> values = Map.of("aliasAnalyze", "anthropic:nonexistent");

        assertThatThrownBy(() ->
                service.validate(form, values, TENANT, PROJECT, "alice", "en"))
                .isInstanceOf(FormValidationException.class)
                .hasMessageContaining("invalid_choice");
    }

    @Test
    void validate_skips_unset_alias_field_without_error() {
        when(modelCatalog.listAll(any(), any())).thenReturn(List.of(
                model("gemini", "gemini-2.5-pro", ModelSize.LARGE)));

        ResolvedSettingForm form = formWithAliasField();
        // Field not in submitted values at all → missing path in validator.
        Map<String, Object> values = Map.of();

        assertThatCode(() ->
                service.validate(form, values, TENANT, PROJECT, "alice", "en"))
                .doesNotThrowAnyException();
    }

    @Test
    void withLiveCascadeValues_populates_choices_from_catalog() {
        when(modelCatalog.listAll(any(), any())).thenReturn(List.of(
                model("gemini", "gemini-2.5-pro", ModelSize.LARGE),
                model("anthropic", "claude-sonnet-4-6", ModelSize.LARGE)));

        ResolvedSettingForm form = formWithAliasField();
        List<FormFieldDto> resolved = service.withLiveCascadeValues(form, TENANT, PROJECT, "alice");

        assertThat(resolved).hasSize(1);
        FormFieldDto field = resolved.get(0);
        assertThat(field.getChoices())
                .extracting(c -> c.getValue())
                .containsExactly("gemini:gemini-2.5-pro", "anthropic:claude-sonnet-4-6");
    }

    // ──────────────────── helpers ────────────────────

    private static ResolvedSettingForm formWithAliasField() {
        FormFieldDto field = FormFieldDto.builder()
                .name("aliasAnalyze")
                .type("select")
                .label(Map.of("en", "Alias analyze"))
                .choicesFrom("ai-models")
                .bindsTo(BindsToDto.builder().key("ai.alias.default.analyze").build())
                .build();
        return new ResolvedSettingForm(
                "llm-setup", Map.of("en", "LLM"), Map.of("en", "LLM"),
                null, null, SettingService.SCOPE_PROJECT,
                List.of(field), List.of(), true, List.of("*"),
                SettingFormSource.RESOURCE);
    }

    private static ModelInfo model(String provider, String name, ModelSize size) {
        return new ModelInfo(provider, name, 200000, 8192, size,
                Set.<ModelCapability>of(), 60, 2);
    }
}
