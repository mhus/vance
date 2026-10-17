package de.mhus.vance.api.form;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One live suggestion for a field with {@code suggestionsFrom}.
 *
 * @param label       what the list shows ("Anna Berger")
 * @param description second line ("anna@meridian.example", "4 addresses")
 * @param values      what choosing it inserts — one value, or several for a
 *                    group (the UI inserts each, so the reader sees and can
 *                    drop every single one before sending)
 * @param kind        a hint for the icon: {@code person}, {@code org}, {@code group}
 * @param note        a caveat shown with the choice ("1 member without an
 *                    address"), {@code null} when there is none
 */
@GenerateTypeScript("form")
public record FormSuggestionDto(
        String label,
        @Nullable String description,
        List<String> values,
        @Nullable String kind,
        @Nullable String note) {

    public FormSuggestionDto {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
