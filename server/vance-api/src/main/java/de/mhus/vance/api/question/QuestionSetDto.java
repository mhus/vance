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
import org.jspecify.annotations.Nullable;

/**
 * Several questions asked at once, each answered separately. Travels in the
 * chat as a {@code questions} fence (kind {@code questions}); answered with a
 * {@link QuestionSetAnswerDto} carrying the same {@link #id}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("question")
public class QuestionSetDto {

    @NotBlank
    private String id = "";

    /** Round of the asking loop this set belongs to (1-based). */
    private int round;

    /** One sentence of context shown above the questions. */
    private @Nullable String intro;

    @Builder.Default
    private List<QuestionDto> questions = new ArrayList<>();
}
