package de.mhus.vance.shared.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.form.FormChoiceDto;
import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormValidatorTest {

    private final FormValidator validator = new FormValidator();

    @Test
    void requiredString_missing_isError() {
        FormFieldDto field = FormFieldDto.builder()
                .name("title").type("string").required(true)
                .label(Map.of("en", "Title")).build();

        assertThatThrownBy(() -> validator.validate(List.of(field), Map.of()))
                .isInstanceOf(FormValidationException.class)
                .satisfies(ex -> {
                    FormValidationException fve = (FormValidationException) ex;
                    assertThat(fve.getErrors())
                            .singleElement()
                            .satisfies(e -> {
                                assertThat(e.field()).isEqualTo("title");
                                assertThat(e.error()).isEqualTo("required");
                            });
                });
    }

    @Test
    void requiredString_blank_isError() {
        FormFieldDto field = FormFieldDto.builder()
                .name("title").type("string").required(true)
                .label(Map.of("en", "Title")).build();

        assertThatThrownBy(() -> validator.validate(List.of(field), Map.of("title", "  ")))
                .isInstanceOf(FormValidationException.class);
    }

    @Test
    void optionalField_absent_isOk() {
        FormFieldDto field = FormFieldDto.builder()
                .name("note").type("textarea").required(false)
                .label(Map.of("en", "Note")).build();

        assertThatCode(() -> validator.validate(List.of(field), Map.of())).doesNotThrowAnyException();
    }

    @Test
    void integer_bounds_areEnforced() {
        FormFieldDto field = FormFieldDto.builder()
                .name("priority").type("integer").required(true)
                .integerMin(1).integerMax(5)
                .label(Map.of("en", "Priority")).build();

        assertThatThrownBy(() -> validator.validate(List.of(field), Map.of("priority", "0")))
                .isInstanceOf(FormValidationException.class)
                .hasMessageContaining("below_min");

        assertThatThrownBy(() -> validator.validate(List.of(field), Map.of("priority", "6")))
                .isInstanceOf(FormValidationException.class)
                .hasMessageContaining("above_max");

        assertThatCode(() -> validator.validate(List.of(field), Map.of("priority", "3")))
                .doesNotThrowAnyException();
    }

    @Test
    void select_rejects_unknown_choice() {
        FormFieldDto field = FormFieldDto.builder()
                .name("tone").type("select").required(true)
                .label(Map.of("en", "Tone"))
                .choices(List.of(
                        FormChoiceDto.builder().value("formal").build(),
                        FormChoiceDto.builder().value("casual").build()))
                .build();

        assertThatThrownBy(() -> validator.validate(List.of(field), Map.of("tone", "snarky")))
                .isInstanceOf(FormValidationException.class)
                .hasMessageContaining("invalid_choice");
    }

    @Test
    void multiSelect_acceptsAllowedSubset() {
        FormFieldDto field = FormFieldDto.builder()
                .name("tags").type("multi_select").required(true)
                .label(Map.of("en", "Tags"))
                .choices(List.of(
                        FormChoiceDto.builder().value("a").build(),
                        FormChoiceDto.builder().value("b").build()))
                .build();

        assertThatCode(() -> validator.validate(
                List.of(field), Map.of("tags", List.of("a"))))
                .doesNotThrowAnyException();
    }

    @Test
    void repeat_min_isEnforced() {
        FormFieldDto member = FormFieldDto.builder()
                .name("name").type("string").required(true)
                .label(Map.of("en", "Name")).build();
        FormFieldDto repeat = FormFieldDto.builder()
                .name("members").type("repeat").min(2)
                .label(Map.of("en", "Members"))
                .item(List.of(member))
                .build();

        assertThatThrownBy(() -> validator.validate(
                List.of(repeat),
                Map.of("members", List.of(Map.of("name", "Alice")))))
                .isInstanceOf(FormValidationException.class)
                .hasMessageContaining("too_few_entries");
    }

    @Test
    void repeat_validatesNested_requiredFields() {
        FormFieldDto member = FormFieldDto.builder()
                .name("name").type("string").required(true)
                .label(Map.of("en", "Name")).build();
        FormFieldDto repeat = FormFieldDto.builder()
                .name("members").type("repeat").min(1)
                .label(Map.of("en", "Members"))
                .item(List.of(member))
                .build();

        assertThatThrownBy(() -> validator.validate(
                List.of(repeat),
                Map.of("members", List.of(Map.of(), Map.of("name", "Bob")))))
                .isInstanceOf(FormValidationException.class)
                .satisfies(ex -> {
                    FormValidationException fve = (FormValidationException) ex;
                    assertThat(fve.getErrors())
                            .anyMatch(e -> e.field().equals("members[0].name")
                                    && e.error().equals("required"));
                });
    }

    @Test
    void boolean_acceptsStringEncoding() {
        FormFieldDto field = FormFieldDto.builder()
                .name("active").type("boolean").required(true)
                .label(Map.of("en", "Active")).build();

        assertThatCode(() -> validator.validate(List.of(field), Map.of("active", "true")))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(List.of(field), Map.of("active", true)))
                .doesNotThrowAnyException();
    }

    // ──────────────── showIf: conditional fields are not "required" ────────────────

    @Test
    void requiredField_behindShowIf_blankSubmission_isAccepted() {
        // The vault form: the provider select defaults to `settings`, which needs
        // no connection, so every Infisical-only field arrives blank. Reporting
        // `required` for them made the form return 422 on every save — while the
        // apply-time planner, which does evaluate the expression, would have
        // skipped their bindings entirely.
        FormFieldDto conditional = FormFieldDto.builder()
                .name("baseUrl").type("string").required(true)
                .showIf("type == 'infisical'")
                .label(Map.of("en", "Base URL")).build();

        assertThatCode(() -> validator.validate(
                List.of(conditional), Map.of("type", "settings")))
                .doesNotThrowAnyException();
    }

    @Test
    void requiredField_behindShowIf_stillTypeChecksWhatIsSubmitted() {
        // Exempt from required-presence, not from validation: a value that IS
        // there is checked as strictly as any other.
        FormFieldDto conditional = FormFieldDto.builder()
                .name("port").type("integer").required(true)
                .integerMin(1).integerMax(65535)
                .showIf("type == 'infisical'")
                .label(Map.of("en", "Port")).build();

        assertThatThrownBy(() -> validator.validate(
                List.of(conditional), Map.of("port", "99999")))
                .isInstanceOf(FormValidationException.class)
                .hasMessageContaining("above_max");
    }

    @Test
    void requiredField_withWriteIfButNoShowIf_staysRequired() {
        // writeIf decides whether the target setting is written or deleted — it
        // says nothing about whether the user was shown a box to fill in. Only
        // showIf exempts.
        FormFieldDto field = FormFieldDto.builder()
                .name("token").type("string").required(true)
                .writeIf("enabled")
                .label(Map.of("en", "Token")).build();

        assertThatThrownBy(() -> validator.validate(List.of(field), Map.of()))
                .isInstanceOf(FormValidationException.class)
                .hasMessageContaining("required");
    }

    @Test
    void requiredField_withBlankShowIf_staysRequired() {
        // A blank expression is not a condition. Treating it as one would turn a
        // YAML typo into a silently unenforced field.
        FormFieldDto field = FormFieldDto.builder()
                .name("title").type("string").required(true)
                .showIf("   ")
                .label(Map.of("en", "Title")).build();

        assertThatThrownBy(() -> validator.validate(List.of(field), Map.of()))
                .isInstanceOf(FormValidationException.class)
                .hasMessageContaining("required");
    }

    @Test
    void unconditionalSiblingOfAConditionalField_isStillRequired() {
        // The exemption is per field, not per form — the unconditional half of a
        // setting form keeps its enforcement.
        FormFieldDto type = FormFieldDto.builder()
                .name("type").type("string").required(true)
                .label(Map.of("en", "Provider")).build();
        FormFieldDto conditional = FormFieldDto.builder()
                .name("baseUrl").type("string").required(true)
                .showIf("type == 'infisical'")
                .label(Map.of("en", "Base URL")).build();

        assertThatThrownBy(() -> validator.validate(List.of(type, conditional), Map.of()))
                .isInstanceOf(FormValidationException.class)
                .satisfies(ex -> assertThat(((FormValidationException) ex).getErrors())
                        .singleElement()
                        .satisfies(e -> assertThat(e.field()).isEqualTo("type")));
    }

    @Test
    void unknownType_isError() {
        FormFieldDto field = FormFieldDto.builder()
                .name("x").type("color-picker").required(true)
                .label(Map.of("en", "X")).build();

        assertThatThrownBy(() -> validator.validate(List.of(field), Map.of("x", "#ffffff")))
                .isInstanceOf(FormValidationException.class)
                .hasMessageContaining("unknown_field_type");
    }
}
