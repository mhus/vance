package de.mhus.vance.api.followup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Single follow-up suggestion returned by the service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("followup")
public class FollowUpSuggestionDto {

    /** The suggested follow-up text. Always non-empty. */
    private String text;

    /**
     * Optional classification label the LLM may attach
     * (e.g. {@code "question"}, {@code "clarification"},
     * {@code "completion"}). Free-form; UIs are free to ignore it.
     */
    private @Nullable String kind;
}
