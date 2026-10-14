package de.mhus.vance.api.question;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** The answer to one {@link QuestionDto}, matched by {@link #key}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("question")
public class QuestionAnswerDto {

    @NotBlank
    private String key = "";

    @Builder.Default
    private QuestionOutcome outcome = QuestionOutcome.ANSWERED;

    /** Chosen option label or free text — set for {@link QuestionOutcome#ANSWERED}. */
    private @Nullable String value;
}
