package de.mhus.vance.api.question;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The structured answers to one {@link QuestionSetDto}. Partial answers are
 * valid: a question without an entry counts as {@link QuestionOutcome#LATER}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("question")
public class QuestionSetAnswerDto {

    /** {@link QuestionSetDto#getId()} of the set being answered. */
    @NotBlank
    private String setId = "";

    @Builder.Default
    private List<QuestionAnswerDto> answers = new ArrayList<>();
}
