package de.mhus.vance.api.followup;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response of {@code POST /brain/{tenant}/follow-up/{project}}.
 *
 * <p>Empty {@code suggestions} list is a valid response (HTTP 200) —
 * means the LLM couldn't produce useful follow-ups for the given
 * input.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("followup")
public class FollowUpResponseDto {

    /** Suggestions, capped at {@code FollowUpRequestDto.count}. Never null. */
    private List<FollowUpSuggestionDto> suggestions;
}
