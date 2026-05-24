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
