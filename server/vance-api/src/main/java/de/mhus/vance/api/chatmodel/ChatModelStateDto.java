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

    /**
     * Effective model, resolved to {@code providerInstance:modelName}. When
     * it cannot be resolved ({@link #modelError} set) this is the raw spec
     * the process is configured with, or empty when there is none.
     */
    private String model;

    /**
     * Why {@link #model} cannot be resolved — e.g. an override naming a
     * provider instance that was removed since. {@code null} when it
     * resolves. The state is still reported so a picker can replace or
     * clear the broken choice.
     */
    private @Nullable String modelError;

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

    /**
     * The recipe's reasoning level, lowercase — what {@code //thinking clear}
     * returns to.
     */
    private String recipeThinking;

    /** {@code true} when {@link #thinking} comes from a runtime {@code //thinking} override. */
    private boolean thinkingOverridden;

    /**
     * Whether the effective model honours a reasoning level other than
     * {@code off}; {@code false} while {@link #modelError} is set.
     */
    private boolean modelSupportsThinking;
}
