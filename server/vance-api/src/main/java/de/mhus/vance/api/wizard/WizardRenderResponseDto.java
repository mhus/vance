package de.mhus.vance.api.wizard;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for {@code POST /brain/{tenant}/wizards/{name}/render}.
 * Contains the fully rendered Pebble {@code promptTemplate} ready to
 * be dropped into the chat input field.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("wizard")
public class WizardRenderResponseDto {

    private String prompt;
}
