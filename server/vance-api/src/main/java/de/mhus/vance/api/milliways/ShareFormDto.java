package de.mhus.vance.api.milliways;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import de.mhus.vance.api.form.FormFieldDto;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The input a handler needs before it can share, declared by the handler
 * itself. Fields use the shared {@code fields:} grammar, so the Web-UI
 * renders them with the same {@code FormFields.vue} it uses for wizards,
 * setting-forms and document-templates.
 *
 * <p>Dynamic option lists arrive <em>already filled</em> in
 * {@link FormFieldDto#getChoices()} — never as a {@code choicesFrom}
 * marker. That marker is bound to the setting-form resolution and knows
 * two sources; a handler knows its own options (and, for recipients, which
 * of them the sharer is actually allowed to reach). See
 * {@code planning/milliways-sharing.md} §5.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("milliways")
public class ShareFormDto {

    private String handlerId;

    @Builder.Default
    private List<FormFieldDto> fields = new ArrayList<>();
}
