package de.mhus.vance.shared.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.form.FormFieldDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FormFieldYamlParserTest {

    @Test
    void parses_suggestionsFrom_on_string_field() {
        List<FormFieldDto> fields = FormFieldYamlParser.parseFields(
                List.of(
                        Map.of(
                                "name",
                                "to",
                                "type",
                                "string",
                                "label",
                                Map.of("en", "To"),
                                "suggestionsFrom",
                                "mail-recipients"),
                        Map.of("name", "subject", "type", "string", "label", Map.of("en", "Subject"))),
                "fields");

        assertThat(fields).hasSize(2);
        assertThat(fields.get(0).getSuggestionsFrom()).isEqualTo("mail-recipients");
        assertThat(fields.get(1).getSuggestionsFrom()).isNull();
    }

    @Test
    void blank_suggestionsFrom_is_ignored() {
        List<FormFieldDto> fields = FormFieldYamlParser.parseFields(
                List.of(Map.of("name", "to", "type", "string", "label", Map.of("en", "To"), "suggestionsFrom", "  ")),
                "fields");

        assertThat(fields.get(0).getSuggestionsFrom()).isNull();
    }

    @Test
    void rejects_suggestionsFrom_on_non_string_field() {
        assertThatThrownBy(() -> FormFieldYamlParser.parseFields(
                        List.of(Map.of(
                                "name",
                                "to",
                                "type",
                                "textarea",
                                "label",
                                Map.of("en", "To"),
                                "suggestionsFrom",
                                "mail-recipients")),
                        "fields"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields[0].suggestionsFrom");
    }
}
