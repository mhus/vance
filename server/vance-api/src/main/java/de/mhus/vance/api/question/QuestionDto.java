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

/** One question inside a {@link QuestionSetDto}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("question")
public class QuestionDto {

    /**
     * Stable key the answer refers back to — for Wonko the topic key of its
     * register, so the answer lands on the topic, not on the wording.
     */
    @NotBlank
    private String key = "";

    @NotBlank
    private String text = "";

    @Builder.Default
    private QuestionKind kind = QuestionKind.TEXT;

    /** Options of a {@link QuestionKind#CHOICE} question; empty otherwise. */
    @Builder.Default
    private List<QuestionOptionDto> options = new ArrayList<>();

    /** The asker's assumption for a {@link QuestionKind#CONFIRM} question. */
    private @Nullable String proposal;

    /** Where the question comes from — a quote or location, for orientation. */
    private @Nullable String context;

    @Builder.Default
    private QuestionWeight weight = QuestionWeight.IMPORTANT;
}
