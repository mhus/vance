package de.mhus.vance.api.chatmodel;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Model and reasoning level a chat process runs with — the structured
 * value of the {@code //model} engine command.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("chatmodel")
public class ChatModelStateDto {

    /** Effective model, resolved to {@code providerInstance:modelName}. */
    private String model;

    /** {@code true} when {@link #model} comes from a runtime {@code //model} override. */
    private boolean modelOverridden;

    /**
     * The recipe's model resolved to {@code providerInstance:modelName} —
     * what {@code //model clear} returns to. {@code null} when it cannot be
     * resolved.
     */
    private @Nullable String recipeModel;

    /** Effective reasoning level, lowercase ({@code off} … {@code high}). */
    private String thinking;

    /** {@code true} when {@link #thinking} comes from a runtime {@code //thinking} override. */
    private boolean thinkingOverridden;

    /** Whether the effective model honours a reasoning level other than {@code off}. */
    private boolean modelSupportsThinking;
}
